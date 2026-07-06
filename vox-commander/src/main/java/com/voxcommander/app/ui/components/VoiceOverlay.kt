package com.voxcommander.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin

@Composable
fun VoiceOverlay(
    isListening: Boolean,
    volumeFlow: StateFlow<Float>,
    partialTranscriptionFlow: StateFlow<String>
) {
    val volume by volumeFlow.collectAsStateWithLifecycle()
    val partialText by partialTranscriptionFlow.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = isListening,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Waveform visualizer
                Box(
                    modifier = Modifier
                        .height(120.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    WaveformVisualizer(volume)
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Partial Transcription
                Surface(
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (partialText.isEmpty()) "Listening..." else partialText,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun WaveformVisualizer(volume: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val maxAmplitude = height / 2.5f
        
        // Normalize volume for visualization
        val normalizedVolume = (volume / 4000f).coerceIn(0.15f, 1f)
        val currentAmplitude = maxAmplitude * normalizedVolume

        val points = 80
        val step = width / points

        for (i in 0 until points) {
            val x = i * step
            val y = centerY + currentAmplitude * sin(i * 0.25f + phase).toFloat()
            
            drawCircle(
                color = Color.Cyan.copy(alpha = 0.9f),
                radius = 3.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(x, y)
            )
        }
    }
}
