package com.voxapps.design.picklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
    anchor: @Composable (label: String, onClick: () -> Unit) -> Unit = { label, onClick ->
        PicklistButtonAnchor(label, onClick)
    },
    /** Whether the menu spans the width it is given. False for an inline anchor, where a menu wider
     *  than the button it drops from reads as belonging to the whole row rather than to the value. */
    menuFillsWidth: Boolean = true,
    below: @Composable () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    // The width belongs to the anchor, not to this box: a full-width button fills it, an inline one
    // does not, and a box that always filled would stretch the second kind across its row.
    Box(modifier = modifier) {
        anchor(selected?.let(itemLabel) ?: noneLabel.orEmpty()) { expanded = true }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = if (menuFillsWidth) Modifier.fillMaxWidth() else Modifier
        ) {
            noneLabel?.let { label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onNoneSelected()
                        expanded = false
                    }
                )
            }

            actionLabel?.let { label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onAction()
                        expanded = false
                    }
                )
            }

            items.forEach { item ->
                val enabled = itemEnabled(item)
                DropdownMenuItem(
                    leadingIcon = itemLeading?.let { leading -> { leading(item) } },
                    text = {
                        Text(
                            text = itemLabel(item) + if (enabled) itemNote(item) else disabledSuffix(item),
                            color = if (enabled) LocalContentColor.current
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    onClick = {
                        if (enabled) {
                            onSelect(item)
                            expanded = false
                        }
                    },
                    enabled = enabled
                )
            }
        }
    }

    below()
}

/** The default anchor: a full-width button showing the current value. */
@Composable
fun PicklistButtonAnchor(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth(), enabled = enabled) {
        Text(label)
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
