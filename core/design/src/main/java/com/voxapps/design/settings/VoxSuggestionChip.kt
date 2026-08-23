package com.voxapps.design.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.design.VoxSemanticColors

/**
 * A value offered next to the field it concerns.
 *
 * Two colours, and the difference is not decoration. Green means the app has a value it believes and
 * is only asking whether to use it; accepting one fills a field and nothing more. Amber means the
 * opposite — nothing identified this, the chip is a question, and accepting it teaches something
 * that will decide how every later message of the same shape is read. A person about to do the
 * second thing should never think they are doing the first.
 *
 * Shared because the same offer now appears in two places — beside a field being edited, and on a
 * capture waiting for review — and a colour that means "this is permanent" cannot be allowed to
 * drift between them. Strings arrive resolved: `:core:design` has no LanguageManager.
 */
@Composable
fun VoxSuggestionChip(
    label: String,
    asking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** False renders it unfilled — for a chip that toggles rather than acts once. */
    selected: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    dismissContentDescription: String = ""
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leading,
        trailingIcon = onDismiss?.let {
            {
                IconButton(onClick = it, modifier = Modifier.size(18.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = dismissContentDescription,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = if (asking) VoxSemanticColors.asked else VoxSemanticColors.offered,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White
        ),
        modifier = modifier
    )
}
