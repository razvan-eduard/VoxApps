package com.voxcommander.app.ui.screens.main

import com.voxcommander.app.ui.LocalLanguageManager

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.domain.voice.TtsManager
import com.voxcommander.app.state.AppStateManager

/**
 * Overlay shown when TTS is speaking.
 * State 1: Compact — full text shown, max 5 lines, small overlay.
 * State 2: Expanded — when TTS reaches line 4, overlay grows to 2/3 screen.
 * Scroll follows TTS position after expand.
 */
@Composable
fun SpeakingOverlay(

    onStop: () -> Unit = { TtsManager.stop() }
) {
        val languageManager = LocalLanguageManager.current
    val isSpeaking by TtsManager.isSpeakingFlow.collectAsStateWithLifecycle()
    val currentText by TtsManager.currentTextFlow.collectAsStateWithLifecycle()
    val speechRate by TtsManager.speechRateFlow.collectAsStateWithLifecycle()

    // Get overlay text size from settings
    val appStateManager = AppStateManager.get()
    val uiState by (appStateManager?.uiState
        ?: kotlinx.coroutines.flow.MutableStateFlow(com.voxcommander.app.state.AppState.initial())
    ).collectAsStateWithLifecycle()
    val overlayTextSize = uiState.overlayTextSize

    // Runtime speed multiplier (1x, 1.25x, 1.5x, 2x)
    var speedMultiplier by remember { mutableFloatStateOf(1f) }

    if (isSpeaking) {
        val text = currentText
        val effectiveRate = speechRate * speedMultiplier
        val words = if (text.isNotEmpty()) text.split(" ").size else 1
        val estimatedDurationMs = (words * 400f / effectiveRate).toInt().coerceAtLeast(500)
        // Chars per line scales inversely with overlayTextSize (bigger text = fewer chars per line)
        val charsPerLine = (40 / overlayTextSize).toInt().coerceAtLeast(10)
        val compactMaxLines = 5
        val expandAtLine = 4
        val totalLines = if (text.isNotEmpty()) (text.length + charsPerLine - 1) / charsPerLine else 1
        val needsExpand = totalLines > compactMaxLines

        // Track which line TTS is currently reading
        var currentLine by remember { mutableFloatStateOf(0f) }
        var isExpanded by remember { mutableStateOf(false) }

        // Reset state only when text changes (not on speed change)
        LaunchedEffect(text) {
            currentLine = 0f
            isExpanded = false
        }

        // Timer + speed control — restarts on text OR speed change, but keeps isExpanded
        LaunchedEffect(text, speedMultiplier) {
            if (text.isNotEmpty() && needsExpand) {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < estimatedDurationMs) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = (elapsed.toFloat() / estimatedDurationMs).coerceIn(0f, 1f)
                    currentLine = progress * totalLines
                    // Expand when TTS reaches line 4
                    if (!isExpanded && currentLine >= expandAtLine) {
                        isExpanded = true
                    }
                    kotlinx.coroutines.delay(16)
                }
                currentLine = totalLines.toFloat()
            }
        }

        // Apply speed multiplier to TTS engine (handles stop/re-speak internally)
        // Skip initial value to avoid setting rate when overlay first appears
        var hasSpeedChanged by remember { mutableStateOf(false) }
        LaunchedEffect(speedMultiplier) {
            if (hasSpeedChanged) {
                TtsManager.setRuntimeSpeechRate(speedMultiplier)
            }
            hasSpeedChanged = true
        }

        val cornerRadius by animateDpAsState(
            targetValue = if (isExpanded) 20.dp else 14.dp,
            animationSpec = tween(400, easing = FastOutSlowInEasing),
            label = "cornerRadius"
        )

        // Dynamic text style based on overlayTextSize setting
        val baseTextStyle = MaterialTheme.typography.bodyMedium
        val dynamicTextStyle = baseTextStyle.copy(
            fontSize = (baseTextStyle.fontSize.value * overlayTextSize).sp
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (isExpanded) 0.85f else 0.7f)
                    .then(if (isExpanded) Modifier.fillMaxHeight(0.5f) else Modifier.wrapContentHeight()),
                shape = RoundedCornerShape(cornerRadius),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 12.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(6.dp).navigationBarsPadding()
                ) {
                    PulsingAiIcon()

                    if (text.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))

                        if (isExpanded) {
                            TeleprompterText(
                                text = text,
                                currentLine = currentLine,
                                isExpanded = true,
                                textStyle = dynamicTextStyle,
                                overlayTextSize = overlayTextSize,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            TeleprompterText(
                                text = text,
                                currentLine = currentLine,
                                isExpanded = false,
                                textStyle = dynamicTextStyle,
                                overlayTextSize = overlayTextSize,
                                modifier = Modifier
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Speed control row (YouTube-style)
                        SpeedControlRow(
                            speedMultiplier = speedMultiplier,
                            onSpeedChange = { speedMultiplier = it }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(languageManager.getString("stop_speaking") ?: "Stop", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingAiIcon() {
    val infiniteTransition = rememberInfiniteTransition(label = "aiPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val aiPurple = Color(0xFF7C4DFF)
    val aiBlue = Color(0xFF448AFF)

    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            // Animate via graphicsLayer (draw phase) instead of Modifier.size (relayout every frame).
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                    alpha = pulseAlpha
                },
            color = aiPurple,
            shape = RoundedCornerShape(100.dp)
        ) {}
        Icon(
            Icons.Default.GraphicEq,
            contentDescription = "TTS Speaking",
            modifier = Modifier.size(32.dp),
            tint = aiBlue
        )
    }
}

@Composable
private fun SpeedControlRow(
    speedMultiplier: Float,
    onSpeedChange: (Float) -> Unit
) {
    val speeds = listOf(1f, 1.25f, 1.5f, 2f)
    val labels = listOf("1x", "1.25x", "1.5x", "2x")

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        speeds.forEachIndexed { idx, speed ->
            FilterChip(
                selected = speedMultiplier == speed,
                onClick = { onSpeedChange(speed) },
                label = { Text(labels[idx], style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TeleprompterText(
    text: String,
    currentLine: Float,
    isExpanded: Boolean,
    textStyle: TextStyle,
    overlayTextSize: Float = 1.0f,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // Line height scales with overlayTextSize
    val lineHeightDp = 18f * overlayTextSize
    val visibleLines = 4f

    // Scroll follows the current TTS line position when expanded
    // Keep the current line visible but don't scroll past it — subtract lines already visible
    LaunchedEffect(currentLine, isExpanded) {
        if (isExpanded && currentLine > 0) {
            val scrollLine = (currentLine - visibleLines).coerceAtLeast(0f)
            val targetPx = with(density) { (scrollLine * lineHeightDp).dp.toPx() }.toInt()
            val maxScroll = scrollState.maxValue
            scrollState.animateScrollTo(
                value = targetPx.coerceAtMost(maxScroll),
                animationSpec = tween(200, easing = LinearEasing)
            )
        }
    }

    if (isExpanded) {
        Text(
            text = text,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
                .padding(horizontal = 6.dp)
                .verticalScroll(scrollState),
            overflow = TextOverflow.Visible
        )
    } else {
        val compactMaxLines = (5 / overlayTextSize).toInt().coerceIn(2, 5)
        Text(
            text = text,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
                .padding(horizontal = 6.dp),
            maxLines = compactMaxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}
