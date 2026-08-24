package com.voxapps.design.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One line saying the app is still working on something you gave it.
 *
 * The case it exists for: a person dictates an expense, the engine cannot answer yet, and the screen
 * shows nothing at all — so they assume they were not heard and say it again, which is how one
 * utterance becomes three queued requests. A capture that is waiting is not an error and not a
 * failure; it is work in progress, and the only thing missing was saying so.
 *
 * Absent when there is nothing waiting, rather than present and empty: a strip that is always there
 * is furniture, and furniture is not read.
 */
@Composable
fun VoxPendingStrip(
    count: Int,
    text: (Int) -> String,
    modifier: Modifier = Modifier,
    /** Opens whatever shows them. A line that names something and cannot be followed is a line that
     *  gets read once and then ignored. */
    onClick: (() -> Unit)? = null
) {
    AnimatedVisibility(visible = count > 0) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text(count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
