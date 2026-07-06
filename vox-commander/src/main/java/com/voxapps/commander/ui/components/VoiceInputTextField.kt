package com.voxapps.commander.ui.components

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.voice.VoiceManager
import com.voxapps.commander.ui.screens.main.ListeningScreen
import com.voxapps.commander.utils.Logger

@Composable
fun VoiceInputTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    placeholder: @Composable (() -> Unit)? = null,

    modelFilterLang: String,
    voiceProcessor: String,
    isModelOnDevice: Boolean = true,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    onVoiceResult: ((String) -> Unit)? = null
) {
        val languageManager = LocalLanguageManager.current
    // Collect the global listening state to keep UI in sync
    val isGloballyListening by VoiceManager.isListeningFlow.collectAsStateWithLifecycle()
    
    // Local state to track IF this specific field started the recording
    var startedRecordingHere by remember { mutableStateOf(false) }

    // Reset local flag if global state stops
    LaunchedEffect(isGloballyListening) {
        if (!isGloballyListening) {
            startedRecordingHere = false
        }
    }

    val isRecording = isGloballyListening && startedRecordingHere
    var isFocused by remember { mutableStateOf(false) }

    Logger.log("VoiceInputTextField: value='$value', readOnly=$readOnly, isRecording=$isRecording", "WW_UI")

    OutlinedTextField(
        value = if (isRecording) languageManager.getString("recording_status") else value,
        onValueChange = {
            Logger.log("VoiceInputTextField onValueChange: '$it', readOnly=$readOnly", "WW_UI")
            onValueChange(it)
        },
        label = label,
        placeholder = placeholder,
        readOnly = readOnly,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = when {
            isRecording -> OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Red,
                unfocusedBorderColor = Color.Red
            )
            !isFocused -> OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedBorderColor = Color.Transparent
            )
            else -> OutlinedTextFieldDefaults.colors()
        },
        trailingIcon = {
            IconButton(
                onClick = {
                    if (isGloballyListening) {
                        VoiceManager.stopListening()
                    } else {
                        startedRecordingHere = true
                        VoiceManager.startListening(modelFilterLang, voiceProcessor) { transcription ->
                            // IMPORTANT: transcription could be an error message
                            if (transcription.isNotEmpty() && !transcription.startsWith("Error:")) {
                                onValueChange(transcription)
                                onVoiceResult?.invoke(transcription) // Notify the wizard
                            }
                        }
                    }
                },
                enabled = isModelOnDevice && !readOnly && enabled
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = languageManager.getString("content_desc_record"),
                    tint = if (isRecording) Color.Red else if (isModelOnDevice) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    )
}
