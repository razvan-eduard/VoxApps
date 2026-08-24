package com.voxapps.design.icon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A short piece of text standing for a category, a layer or a list — an emoji, in practice.
 *
 * Offered as a set to choose from and a field to type in, because the set can only ever be someone
 * else's idea of what people record. The field takes whatever the keyboard produces, so a person
 * whose shorthand is not in the grid is not told their shorthand is unavailable.
 */
object VoxIcons {

    /**
     * A starting set, grouped the way spending is: what is bought, where it goes, what it is for.
     *
     * A grid rather than a search, because a set small enough to read at a glance is quicker than
     * any query, and because naming these for search would mean naming them in every language.
     */
    val COMMON: List<String> = listOf(
        "🛒", "🥖", "🍎", "🍽️", "☕", "🍺", "🍕",
        "🏠", "💡", "🔥", "💧", "📶", "📱", "💻",
        "🚗", "⛽", "🚌", "✈️", "🚕", "🅿️", "🔧",
        "💊", "🏥", "🦷", "🏋️", "💇", "🧴", "🧼",
        "👕", "👟", "🎁", "📚", "🎬", "🎮", "🎵",
        "🐾", "🌱", "🧸", "🎓", "💼", "🧾", "🏦",
        "💰", "💳", "📈", "🎯", "❤️", "⭐", "❓"
    )

    /**
     * What is safe to store: trimmed, and cut to a couple of characters.
     *
     * A person can paste a sentence into the field, and a sentence is not an icon — it would push
     * every row it appears in out of shape. The limit is characters rather than glyphs on purpose:
     * one emoji is often several code units, and a flag or a skin tone is more still.
     */
    fun sanitised(typed: String?): String? =
        typed?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)

    private const val MAX_LENGTH = 8
}

/**
 * Choosing one, or choosing none.
 *
 * [onPick] receives null for "no icon", which is a choice like any other and needs its own way of
 * being made — a person who set one must be able to take it back without deleting the thing it
 * belongs to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxIconPickerDialog(
    title: String,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    noneLabel: String,
    customLabel: String,
    confirmLabel: String,
    cancelLabel: String,
    icons: List<String> = VoxIcons.COMMON
) {
    var typed by remember { mutableStateOf(selected.orEmpty()) }
    // A sheet, like every other list that covers what is behind it: dragged down to leave, rather
    // than dismissed only by the one button that says so.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    icons.forEach { icon ->
                        val chosen = icon == typed
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (chosen) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { typed = icon },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(icon, fontSize = 20.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(customLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            TextButton(onClick = { onPick(null) }, modifier = Modifier.padding(top = 4.dp)) {
                Text(noneLabel)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(cancelLabel) }
                TextButton(onClick = { onPick(VoxIcons.sanitised(typed)) }) { Text(confirmLabel) }
            }
        }
    }
}
