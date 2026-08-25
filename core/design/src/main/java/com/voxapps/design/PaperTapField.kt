package com.voxapps.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A value on a ruled line: caption above, value below, a rule under it — a field on paper rather
 * than a box drawn around one.
 *
 * Tapping it does something other than typing: it opens a picker, a menu, a date dialog. That is why
 * it is not a text field — a text field that cannot be typed into spends the whole interaction
 * fighting the keyboard for a value the user is choosing rather than writing.
 *
 * The note and expense editors each had their own private copy, identical but for one parameter
 * apiece: one could be disabled, the other could show a suggestion chip. Both are here, and both
 * default to the behaviour the other one had.
 */
@Composable
fun PaperTapField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: @Composable () -> Unit = {},
    /** An offer to fill this field — "the scan says Tesco, use it?" — beside the current value. */
    suggestion: (@Composable () -> Unit)? = null,
    /** Whether [value] stands in for a value rather than being one — "Multiple values", "Leave
     *  unchanged". Drawn in the muted colour captions use, so a glance separates what the field
     *  says from what it is only reporting about itself. */
    placeholder: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    placeholder -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.weight(1f)
            )
            suggestion?.invoke()
            // Nothing to open when the field is disabled, so the affordance goes with it.
            if (enabled) trailingIcon()
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 1.dp)
    }
}
