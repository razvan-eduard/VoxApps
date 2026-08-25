package com.voxapps.design.picklist

import com.voxapps.datahygiene.NameCasing
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Choose one of several things, with whatever describes the chosen one directly beneath.
 *
 * Every settings screen in every app had written this out for itself: a piece of state for whether
 * the menu is open, a button showing the current value, a menu of the alternatives, and a body that
 * has to remember to close the menu in each branch. Around twenty copies, and they had drifted —
 * some menus were full width and some were not, some anchors carried a ▾ and some did not, one
 * handled disabled rows, several hand-wrote their own "None" entry.
 *
 * [below] is where the selection describes itself — a credential field, a connection test, whatever
 * belongs to the chosen thing rather than to the list. It is deliberately not attached to the rows:
 * a test per row would fire a request per item every time the menu opened, which for a list of cloud
 * services is a request per service per glance.
 *
 * [anchor] is what the user taps. The default is the full-width button most settings screens want;
 * [PicklistCompactAnchor] is the inline form for a value sitting at the end of a labelled row. An
 * app with its own field styling passes its own — that is how the note and expense editors keep
 * their paper-like fields without any of that styling reaching this module.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picklist(
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemEnabled: (T) -> Boolean = { true },
    /** Why this row is greyed out, per row: more than one gate can disable an entry, and a single
     *  sentence for all of them names the wrong one for every gate but the first. */
    disabledSuffix: (T) -> String = { "" },
    /** A hint on a row that can still be chosen — "needs an API key" and the like. The greyed-out
     *  reason is [disabledSuffix]; this is for what the user can act on by choosing it. */
    itemNote: (T) -> String = { "" },
    /** When set, a first row meaning "none of them", and what the anchor shows while nothing is
     *  selected. Callers wrote this row by hand and worded it three different ways. */
    noneLabel: String? = null,
    onNoneSelected: () -> Unit = {},
    /** A row that does something other than select — "New category…", which opens a dialog. Drawn
     *  after [noneLabel] and before the items, and it closes the menu like any other row. */
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    /** A swatch, an icon — whatever marks a row out. The colour dot beside a category is the case
     *  this exists for; a list of plain names passes nothing. */
    itemLeading: (@Composable (T) -> Unit)? = null,
    /**
     * A second line under the label, quieter than it — an account's IBAN under its name, a card's
     * masked number under its alias.
     *
     * For what identifies a row rather than what it is called: numbers are what you check a row
     * against and what you would search for, so they are matched by the search box too, but they are
     * not what you read a list by.
     */
    itemSubtitle: ((T) -> String?)? = null,
    /**
     * Correcting what the chosen thing is called, without leaving the field it was chosen in.
     *
     * A name typed wrong once is lived with until there is somewhere to fix it, and the place a
     * person notices it is the record — not a settings page two menus away. Given, the value can be
     * edited in place: held, the closed field becomes a text box; opened, the list carries the same
     * box at the top. Absent, the picklist behaves exactly as it did.
     *
     * What arrives is trimmed and capitalized word by word, never blank — a name is a name whatever
     * keyboard typed it, and the caller stores what it is given.
     */
    onRename: ((from: T, to: String) -> Unit)? = null,
    anchor: @Composable (label: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) -> Unit =
        { label, onClick, onLongClick -> PicklistButtonAnchor(label, onClick, onLongClick = onLongClick) },
    /** Whether the menu spans the width it is given. False for an inline anchor, where a menu wider
     *  than the button it drops from reads as belonging to the whole row rather than to the value. */
    menuFillsWidth: Boolean = true,
    /**
     * When set, a box at the top of the menu that narrows the rows as you type, matched against
     * [itemLabel]. Absent, the menu behaves exactly as it did.
     *
     * Worth having only where the list is as long as the data makes it — every vendor a person has
     * ever paid, every place they have been. A fixed list of four engines is quicker to read than to
     * search, and a box above it is one more thing between someone and the row they can already see.
     *
     * The query is cleared whenever the menu closes: it belongs to the act of finding a row, not to
     * the selection, and a menu that reopens still narrowed hides rows nobody excluded on purpose.
     */
    searchPlaceholder: String? = null,
    /**
     * An extra row offered while a search narrows the list, taking the number of rows that survived
     * — "Show all 12 matching". For a caller whose selection can be a query rather than one value;
     * [onSearchAll] receives the text as typed.
     */
    searchAllLabel: ((Int) -> String)? = null,
    onSearchAll: (String) -> Unit = {},
    /**
     * Whether the rows arrive as a sheet rather than as a menu hanging off the anchor.
     *
     * Defaults to whether a search box was asked for, which is already this component's own signal
     * that the list is as long as the data makes it — and a list that long covers the screen either
     * way, so it should be dismissed the way everything else covering the screen is.
     */
    /**
     * Rows a search can reach that the list does not show.
     *
     * A field naming a bank should offer the banks you deal with, not every bank there is — but the
     * one you are about to deal with for the first time has to be reachable too, and typing three
     * letters is a smaller thing than scrolling seventy-six rows. Shown only while the query is
     * non-blank, after whatever the list itself matched, and never twice.
     */
    extraWhileSearching: List<T> = emptyList(),
    asSheet: Boolean = searchPlaceholder != null,
    below: @Composable () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    // The width belongs to the anchor, not to this box: a full-width button fills it, an inline one
    // does not, and a box that always filled would stretch the second kind across its row.
    var query by remember { mutableStateOf("") }
    // What the name is being changed to, while it is being changed. Null is the ordinary state.
    var draft by remember { mutableStateOf<String?>(null) }
    val shown = remember(items, extraWhileSearching, query) {
        if (query.isBlank()) items
        else {
            val q = query.trim()
            fun matches(item: T) = itemLabel(item).contains(q, ignoreCase = true) ||
                itemSubtitle?.invoke(item)?.contains(q, ignoreCase = true) == true
            val matched = items.filter { matches(it) }
            val known = matched.map { itemLabel(it).lowercase() }.toSet()
            matched + extraWhileSearching.filter {
                itemLabel(it).contains(q, ignoreCase = true) && itemLabel(it).lowercase() !in known
            }
        }
    }

    // The rows, wherever they are drawn — a menu hanging off the anchor, or a sheet covering the
    // screen. Written once, because the difference between the two is where they appear and nothing
    // about what they say.
    val close = { expanded = false; query = "" }
    val rows: @Composable () -> Unit = {
        // At the top, because it is about the thing already chosen rather than about choosing
        // another. The list below keeps every row, this one included: a list that changed shape
        // with the selection would read as rows going missing.
        if (onRename != null && selected != null) {
            PicklistRenameRow(
                value = draft ?: itemLabel(selected),
                onValueChange = { draft = it },
                onSave = {
                    NameCasing.capitalized(draft)?.let { onRename(selected, it) }
                    draft = null
                    close()
                },
                onCancel = { draft = null }
            )
        }
        if (query.isNotBlank() && searchAllLabel != null) {
            PicklistRow(text = searchAllLabel(shown.size)) { onSearchAll(query.trim()); close() }
        }
        noneLabel?.let { label -> PicklistRow(text = label) { onNoneSelected(); close() } }
        actionLabel?.let { label -> PicklistRow(text = label) { onAction(); close() } }
        shown.forEach { item ->
            val enabled = itemEnabled(item)
            PicklistRow(
                text = itemLabel(item) + if (enabled) itemNote(item) else disabledSuffix(item),
                subtitle = itemSubtitle?.invoke(item),
                enabled = enabled,
                leading = itemLeading?.let { leading -> { leading(item) } }
            ) { if (enabled) { onSelect(item); close() } }
        }
    }

    val searchField: @Composable () -> Unit = {
        searchPlaceholder?.let { placeholder ->
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = null)
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }

    Box(modifier = modifier) {
        // Held rather than tapped, the closed field becomes the box that renames it — the shortest
        // path there is from noticing a wrong name to correcting it.
        val editingInPlace = draft != null && !expanded
        if (editingInPlace) {
            PicklistRenameRow(
                value = draft.orEmpty(),
                onValueChange = { draft = it },
                onSave = {
                    val to = NameCasing.capitalized(draft)
                    if (to != null && selected != null) onRename?.invoke(selected, to)
                    draft = null
                },
                onCancel = { draft = null }
            )
        } else {
            anchor(
                selected?.let(itemLabel) ?: noneLabel.orEmpty(),
                { expanded = true },
                if (onRename != null && selected != null) {
                    { draft = itemLabel(selected) }
                } else {
                    null
                }
            )
        }

        if (asSheet) {
            // A list this long covers the screen whichever way it is drawn, and something covering
            // the screen should leave the way everything else does — dragged down. A menu can only
            // be dismissed by tapping the one place it is not.
            if (expanded) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(onDismissRequest = { close() }, sheetState = sheetState) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        searchField()
                        Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            rows()
                        }
                    }
                }
            }
        } else {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { close() },
                modifier = if (menuFillsWidth) Modifier.fillMaxWidth() else Modifier
            ) {
                searchField()
                rows()
            }
        }
    }

    below()
}

/** The default anchor: a full-width button showing the current value. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PicklistButtonAnchor(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null
) {
    if (onLongClick == null) {
        OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth(), enabled = enabled) {
            Text(label)
        }
    } else {
        // The button keeps its own look; the hold is caught by a surface over it, since a button
        // consumes the press it is given and would never let one through.
        Box(modifier = modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                Text(label)
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            )
        }
    }
}

/**
 * The field anchor: a read-only text field with a caption and a ▾, for a value that belongs to a
 * form rather than to a settings row — it lines up with the editable fields around it.
 *
 * The field is genuinely read-only and a transparent surface above it takes the tap, because a text
 * field that opens a menu when focused fights the keyboard for a value that cannot be typed.
 */
@Composable
fun PicklistFieldAnchor(
    /** The field's caption. Null for a field that sits under a heading of its own and would only
     *  repeat it. */
    caption: String?,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = caption?.let { { Text(it) } },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled, onClick = onClick)
        )
    }
}

/**
 * The inline anchor: a small button with a ▾, for a value that sits at the end of its own labelled
 * row rather than under a heading of its own.
 */
@Composable
fun PicklistCompactAnchor(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minWidth: Dp = 120.dp
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.widthIn(min = minWidth),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(label)
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
}

/** One row of a picklist, the same whether it is drawn in a menu or in a sheet. */
/**
 * A name being corrected: the text as it stands, a tick, a cross.
 *
 * The two marks rather than a dialog because the correction is a keystroke or two and a dialog is
 * three taps around it — and because the value stays where the eye already is.
 */
@Composable
private fun PicklistRenameRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = null)
        }
        IconButton(onClick = onSave, enabled = value.isNotBlank()) {
            Icon(Icons.Filled.Check, contentDescription = null)
        }
    }
}

@Composable
private fun PicklistRow(
    text: String,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading?.invoke()
        Column {
            Text(
                text = text,
                color = if (enabled) LocalContentColor.current
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
