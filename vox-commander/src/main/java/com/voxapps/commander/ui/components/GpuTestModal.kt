package com.voxapps.commander.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voxapps.commander.state.GpuTestState

@Composable
fun GpuTestModal(
    gpuTestState: GpuTestState,
    gpuTestPassed: Boolean?,
    onDismiss: () -> Unit,
) {
    val languageManager = com.voxapps.commander.ui.LocalLanguageManager.current
    if (gpuTestState == GpuTestState.IDLE) return

    val isRunning = gpuTestState == GpuTestState.RUNNING
    val isResult = gpuTestState == GpuTestState.RESULT

    Dialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isRunning,
            dismissOnClickOutside = !isRunning,
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
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = languageManager.getString("testing_gpu_compatibility"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = languageManager.getString("testing_gpu_performance"),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = languageManager.getString("gpu_test_timeout"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (isResult && gpuTestPassed != null) {
                        val isSuccess = gpuTestPassed
                        val themeColor = if (isSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                        
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(themeColor.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = themeColor
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text(
                            text = if (isSuccess) languageManager.getString("gpu_ready") else languageManager.getString("incompatible_device"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = themeColor
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = if (isSuccess) {
                                languageManager.getString("gpu_ready_message")
                            } else {
                                languageManager.getString("gpu_failed_message")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = themeColor)
                        ) {
                            Text("OK", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
