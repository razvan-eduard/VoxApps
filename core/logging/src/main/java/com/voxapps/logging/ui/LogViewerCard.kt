package com.voxapps.logging.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.voxapps.logging.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogViewerStrings(
    val sectionTitle: String,
    val clearLabel: String,
    val copyLabel: String,
    val shareLabel: String,
    val noLogsLabel: String
)

/** The ring-buffer viewer originally embedded in Commander's Advanced settings tab — Clear/Copy/
 *  Share actions plus a chronological list, newest first (capped at [Logger]'s 100-entry buffer).
 *  Shared so every app's Logs settings tab looks identical. */
@Composable
fun LogViewerCard(
    logs: List<Logger.LogEntry>,
    strings: LogViewerStrings,
    shareSubject: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = strings.sectionTitle, style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { Logger.clearVerboseLogs() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(strings.clearLabel, style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = {
                        val logText = formatLogs(logs, dateFormat)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(shareSubject, logText))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = logs.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = strings.copyLabel, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(strings.copyLabel, style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = {
                        val logText = formatLogs(logs, dateFormat)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, logText)
                            putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, strings.shareLabel))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = logs.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = strings.shareLabel, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(strings.shareLabel, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (logs.isEmpty()) {
                Text(
                    text = strings.noLogsLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    logs.forEach { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = dateFormat.format(Date(entry.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "[${entry.tag}]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = entry.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatLogs(logs: List<Logger.LogEntry>, dateFormat: SimpleDateFormat): String =
    logs.joinToString("\n") { "[${dateFormat.format(Date(it.timestamp))}] [${it.tag}] ${it.message}" }
