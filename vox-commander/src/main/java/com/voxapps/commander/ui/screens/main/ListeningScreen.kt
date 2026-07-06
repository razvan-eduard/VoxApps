package com.voxapps.commander.ui.screens.main

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.voice.VoiceManager
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.utils.Strings

@Composable
fun ListeningScreen(

    appStateManager: AppStateManager,
    onStop: () -> Unit = { VoiceManager.stopListening() }
) {
        val languageManager = LocalLanguageManager.current
    // TEST: Migrating to collectAsStateWithLifecycle to verify manual Lifecycle sync in WindowManager
    val isListening by VoiceManager.isListeningFlow.collectAsStateWithLifecycle()
    val partialTranscription by VoiceManager.partialTranscriptionFlow.collectAsStateWithLifecycle()
    val volume by VoiceManager.volumeFlow.collectAsStateWithLifecycle()
    val animatedVolume by animateFloatAsState(
        targetValue = volume,
        animationSpec = tween(durationMillis = 100),
        label = "volume"
    )
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    if (isListening) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.85f) // Gap stânga-dreapta
                    .wrapContentHeight(),
                shape = RoundedCornerShape(32.dp), // Toate colțurile rotunjite
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 12.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp).navigationBarsPadding() // Added navigation bars padding
                ) {
                    // Microphone Icon with Volume Visualization
                    Box(
                        modifier = Modifier.size(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Pulsing Volume indicator ring
                        Surface(
                            // Scale/alpha via graphicsLayer (draw phase) — the lambda defers the
                            // animatedVolume read, avoiding recomposition/relayout every frame.
                            modifier = Modifier
                                .size(80.dp)
                                .graphicsLayer {
                                    val s = 1f + (animatedVolume * 0.75f)
                                    scaleX = s
                                    scaleY = s
                                    alpha = (0.1f + (animatedVolume * 0.4f)).coerceIn(0.1f, 0.5f)
                                },
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(100.dp)
                        ) {}

                        Icon(
                            Icons.Default.Mic,
                            contentDescription = languageManager.getString("content_desc_listening"),
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Partial Transcription
                    Text(
                        text = partialTranscription.ifEmpty { languageManager.getString("recording_status") },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Stop Button
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(languageManager.getString("stop_recording_button") ?: "Stop Recording")
                    }
                }
            }
        }
    }
}
