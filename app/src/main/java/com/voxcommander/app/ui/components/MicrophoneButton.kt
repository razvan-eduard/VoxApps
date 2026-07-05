package com.voxcommander.app.ui.components

import com.voxcommander.app.ui.LocalLanguageManager

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.state.VoiceState

@Composable
fun MicrophoneButton(

    appStateManager: AppStateManager,
    isProcessing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
        val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    
    val isCurrentlyProcessing = uiState.voiceState == VoiceState.PROCESSING || isProcessing
    val isRecording = uiState.voiceState == VoiceState.LISTENING_COMMAND

    // Button is enabled only if BOTH Voice and Intent engines are ready
    val isAppReady = uiState.voiceModelReady && uiState.intentModelReady

    val buttonColor by animateColorAsState(
        targetValue = when {
            !isAppReady -> Color.Gray
            isRecording -> MaterialTheme.colorScheme.error // RED
            isCurrentlyProcessing -> Color(0xFFFFA000) // ORANGE
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 300),
        label = "ButtonColor"
    )

    Button(
        onClick = onClick,
        modifier = modifier.size(150.dp),
        shape = MaterialTheme.shapes.extraLarge,
        enabled = isAppReady,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isCurrentlyProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    color = Color.White,
                    strokeWidth = 6.dp
                )
            } else {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = languageManager.getString("content_desc_record"),
                    modifier = Modifier.size(80.dp),
                    tint = if (isAppReady) Color.White else Color.LightGray
                )
            }
        }
    }
}

@Composable
fun ModelNotPresentMessage(

    appStateManager: AppStateManager
) {
        val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!uiState.voiceModelReady) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Voice engine not ready (Download model)",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        
        if (!uiState.intentModelReady) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "AI Intent engine not ready (Download model)",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
