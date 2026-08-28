package com.voxapps.design

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The management-overlay sheet Commander's submenus ride in, as a shared shell: a
 * [ModalBottomSheet] pinned to full size with no drag handle, dismissed by dragging the content
 * down from the top of its scroll (the sheet's own nested-scroll behaviour), by scrim tap, or by
 * back. Full size is the load-bearing part — a wrap-content sheet keeps re-settling while tall
 * content lays out and ignores drags until it stops, where a full-size target is one fast slide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxFullscreenSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = null,
        modifier = modifier.fillMaxSize()
    ) {
        content()
    }
}
