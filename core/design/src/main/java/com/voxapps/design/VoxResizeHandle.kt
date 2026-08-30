package com.voxapps.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A grip on a card's bottom edge that resizes the room above it. Dragging reports each raw delta
 * through [onDragBy] — the caller accumulates it into its own snapshot state and clamps, so every
 * event lands on the latest value with no composition round-trip between finger and height.
 * Tapping fires [onTapToggleMax], which callers wire to a jump between "most of the screen" and
 * whatever size the drag had set; callers clear their maxed flag inside [onDragBy] so a drag
 * always takes over from the maxed state, and the manual size is theirs to keep.
 */
@Composable
fun VoxResizeHandle(
    onDragBy: (Float) -> Unit,
    onTapToggleMax: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectTapGestures { onTapToggleMax() }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> onDragBy(dragAmount) }
            }
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
        )
    }
}
