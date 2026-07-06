package com.voxcommander.app.ui.screens.settings

import com.voxcommander.app.ui.LocalLanguageManager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.utils.Logger
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun VerboseLoggingTab(

    verboseLoggingEnabled: Boolean // HOISTED STATE
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val logs by Logger.verboseLogs.collectAsStateWithLifecycle()
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Text(text = languageManager.getString("verbose_logging_section"), style = MaterialTheme.typography.titleMedium)

    Spacer(modifier = Modifier.height(8.dp))

    // Action buttons row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Clear Logs Button
        Button(
            onClick = { Logger.clearVerboseLogs() },
            modifier = Modifier.weight(1f),
            enabled = verboseLoggingEnabled
        ) {
            Text(languageManager.getString("clear_logs"))
        }

        // Copy Button
        Button(
            onClick = {
                val logText = logs.joinToString("\n") { logEntry ->
                    val timestamp = dateFormat.format(Date(logEntry.timestamp))
                    "[$timestamp] [${logEntry.tag}] ${logEntry.message}"
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("VoxCommander Logs", logText)
                clipboard.setPrimaryClip(clip)
            },
            modifier = Modifier.weight(1f),
            enabled = verboseLoggingEnabled && logs.isNotEmpty()
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = languageManager.getString("copy_button"))
            Spacer(modifier = Modifier.width(4.dp))
            Text(languageManager.getString("copy_button"))
        }

        // Share Button
        Button(
            onClick = {
                val logText = logs.joinToString("\n") { logEntry ->
                    val timestamp = dateFormat.format(Date(logEntry.timestamp))
                    "[$timestamp] [${logEntry.tag}] ${logEntry.message}"
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, logText)
                    putExtra(Intent.EXTRA_SUBJECT, "VoxCommander Logs")
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
            },
            modifier = Modifier.weight(1f),
            enabled = verboseLoggingEnabled && logs.isNotEmpty()
        ) {
            Icon(Icons.Default.Share, contentDescription = languageManager.getString("share_button"))
            Spacer(modifier = Modifier.width(4.dp))
            Text(languageManager.getString("share_button"))
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (verboseLoggingEnabled) {
        if (logs.isEmpty()) {
            Text(
                text = languageManager.getString("no_logs"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                logs.forEach { logEntry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text(
                                text = dateFormat.format(Date(logEntry.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "[${logEntry.tag}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = logEntry.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    } else {
        Text(
            text = languageManager.getString("verbose_logging_disabled"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
