package com.voxapps.commander.ui.components

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.state.AppScanState

@Composable
fun AppScanModal(
    scanState: AppScanState,
    onDismiss: () -> Unit
) {
        val languageManager = LocalLanguageManager.current
    if (scanState is AppScanState.Idle) return

    val isScanning = scanState is AppScanState.Scanning
    val isDone = scanState is AppScanState.Done

    Dialog(
        onDismissRequest = { if (!isScanning) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isScanning,
            dismissOnClickOutside = !isScanning,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isScanning) {
                        val state = scanState as AppScanState.Scanning
                        val progress = if (state.total > 0) state.current.toFloat() / state.total else 0f

                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = languageManager.getString("scanning_apps_title"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Progress bar
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Counter
                        Text(
                            text = "${state.current} / ${state.total}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Current app name with animation
                        AnimatedContent(
                            targetState = state.appName,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label = "app_name"
                        ) { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (isDone) {
                        val state = scanState as AppScanState.Done
                        val durationSec = state.durationMs / 1000.0

                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFF2E7D32).copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color(0xFF2E7D32)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = languageManager.getString("scan_complete"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = languageManager.getString("scan_complete_message")
                                .replace("{count}", state.totalApps.toString())
                                .replace("{time}", String.format("%.1f", durationSec)),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("OK", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
