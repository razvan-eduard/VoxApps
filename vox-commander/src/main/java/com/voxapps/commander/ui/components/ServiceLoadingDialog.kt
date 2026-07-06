package com.voxapps.commander.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.commander.state.ServiceLoadingState

/**
 * Reusable loading dialog shown when starting/stopping a service that loads large models.
 *
 * Shows: "{Starting/Stopping} {serviceName} service with {engineName} - {modelName}"
 * with a circular progress indicator. Optional cancel button.
 */
@Composable
fun ServiceLoadingDialog(
    state: ServiceLoadingState,
    onCancel: (() -> Unit)? = null
) {
    if (!state.isActive) return

    val action = if (state.isStopping) "Stopping" else "Starting"
    val message = buildString {
        append(action)
        append(" ")
        append(state.serviceName)
        append(" service")
        if (state.engineName.isNotBlank()) {
            append(" with ")
            append(state.engineName)
        }
        if (state.modelName.isNotBlank()) {
            append(" - ")
            append(state.modelName)
        }
        append("...")
    }

    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp
                )
                Text(
                    text = "$action service",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    )
}
