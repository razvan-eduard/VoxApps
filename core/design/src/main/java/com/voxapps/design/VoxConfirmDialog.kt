package com.voxapps.design

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Are you sure — and for the destructive kind, are you sure enough to wait.
 *
 * [countdownSeconds] holds the confirming button shut for that many seconds, counting down on its
 * own label. It exists for one specific failure: the second tap of a double tap landing on a button
 * that was not there when the first one started. Everything reachable by muscle memory alone is
 * reachable by accident, and the only reliable defence against a reflex is a few seconds in which
 * there is nothing to hit.
 *
 * [destructive] colours the confirming button as the error it is. Both default to off, so an
 * ordinary confirmation is an ordinary dialog.
 */
@Composable
fun VoxConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    countdownSeconds: Int = 0
) {
    var remaining by remember { mutableIntStateOf(countdownSeconds) }
    LaunchedEffect(countdownSeconds) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = remaining == 0) {
                Text(
                    if (remaining > 0) "$confirmLabel ($remaining)" else confirmLabel,
                    color = when {
                        remaining > 0 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        }
    )
}
