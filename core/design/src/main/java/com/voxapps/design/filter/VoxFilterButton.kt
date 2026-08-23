package com.voxapps.design.filter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What a narrowed list says about itself.
 *
 * A list that has been filtered looks exactly like a list that is short, and the difference matters
 * more than anything else on screen: one is missing records on purpose and the other is not. So the
 * control that opens the filters is also the thing that reports them — naming what is in force
 * rather than showing a funnel icon that looks the same either way.
 */
object VoxFilterSummary {

    /** Between two things that are both in force. Not a comma: values contain those. */
    const val SEPARATOR = " · "

    /** Appended to say the control opens something rather than doing something. */
    const val OPENS_SOMETHING = "…"

    /**
     * The active filters, named, or [whenNothingActive] when none are.
     *
     * Nulls and blanks drop out, so a caller assembles its parts in a fixed order and lets the ones
     * that are switched off disappear — the order stays stable as filters come and go, which is what
     * lets a person read the same button twice and find the same thing in the same place.
     */
    fun of(parts: List<String?>, whenNothingActive: String): String =
        parts.filterNot { it.isNullOrBlank() }
            .joinToString(SEPARATOR)
            .ifEmpty { whenNothingActive }
}

/**
 * The one control for a narrowed list: it says what is in force, opens the filters, and clears them.
 *
 * Three jobs in one chip rather than an icon somewhere else and a summary somewhere else again. The
 * clear is a trailing ✕ that appears only when there is something to clear, so the button is never
 * offering to undo nothing.
 */
@Composable
fun VoxFilterButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = Icons.Filled.Tune
) {
    InputChip(
        selected = active,
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                label + VoxFilterSummary.OPENS_SOMETHING,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        trailingIcon = if (active) {
            {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = clearContentDescription,
                    modifier = Modifier.size(18.dp).clickable(onClick = onClear)
                )
            }
        } else null
    )
}
