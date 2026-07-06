package com.voxapps.commander.domain.voice

import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.SttEngine
import com.voxapps.commander.domain.engine.whisper.WhisperCppSttEngine
import com.voxapps.commander.domain.engine.google.GoogleSttEngine
import com.voxapps.commander.domain.engine.vosk.VoskSttEngine
import com.voxapps.commander.domain.engine.whisper.WhisperSttEngine
import com.voxapps.commander.domain.voice.WakeWordProfile
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.state.VoiceState
import com.voxapps.commander.utils.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Singleton VoiceManager to handle real audio capture and STT lifecycle.
 * Serializes access to native resources via AppStateManager's Mutex.
 * Reactively manages engine instances based on AppStateManager settings.
 */
object VoiceManager {
    private const val TAG = Strings.Tags.VOICE_MANAGER

    private var whisperCppEngine: WhisperCppSttEngine? = null
    private var whisperApiEngine: WhisperSttEngine? = null
    private var googleSttEngine: GoogleSttEngine? = null
    private var voskSttEngine: VoskSttEngine? = null

    @android.annotation.SuppressLint("StaticFieldLeak")
    private var context: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObservationJob: Job? = null

    private val isListeningFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _isListeningFlow = MutableStateFlow(false)
    val isListeningFlow = _isListeningFlow.asStateFlow()

    private var settingsRepo: SettingsRepository? = null
    private var appStateManager: AppStateManager? = null

    // Calibration values from WakeWordProfile for volume normalization
    private var calibratedNoiseFloor = 0f
    private var calibratedMaxRms = 0f

    // Audio focus for pausing music during command listening
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Cached settings to avoid runBlocking on hot paths (updated in startListening)
    @Volatile private var cachedSttSensitivity: String = "medium"
    @Volatile private var cachedWakeWordProfileJson: String? = null

    private val _volumeFlow = MutableStateFlow(0f)
    val volumeFlow: StateFlow<Float> = _volumeFlow.asStateFlow()

    private val _partialTranscriptionFlow = MutableStateFlow("")
    val partialTranscriptionFlow: StateFlow<String> = _partialTranscriptionFlow.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    fun setCalibrationListening(active: Boolean) {
        _isListeningFlow.value = active
        if (active) {
            appStateManager?.setVoiceState(VoiceState.LISTENING_COMMAND)
        } else {
            _volumeFlow.value = 0f
        }
    }

    fun setCalibrationVolume(volume: Float) {
        _volumeFlow.value = volume
    }

    class RecordingQuality(val sampleRate: Int, val description: String) {
        companion object {
            val MEDIUM = RecordingQuality(16000, "16kHz Mono")
        }
    }

    private var currentQuality: RecordingQuality = RecordingQuality.MEDIUM

    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val SILENCE_TIMEOUT_MS = 2000L

    private fun getSilenceThreshold(): Float {
        return when (cachedSttSensitivity) {
            "low" -> 0.06f    // less sensitive — ignores background noise, tapping
            "high" -> 0.01f   // very sensitive — picks up quiet speech
            else -> 0.03f     // medium
        }
    }

    /**
     * Requests transient audio focus to pause music while listening for a command.
     * This ensures the STT engine can hear the user clearly without music interference.
     */
    private fun requestListeningAudioFocus() {
        val ctx = context ?: return
        if (audioManager == null) {
            audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        val am = audioManager ?: return

        audioFocusRequest = com.voxapps.commander.utils.AudioFocusHelper.requestFocus(
            am, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        Logger.log("Listening audio focus requested (pausing music)", TAG)
    }

    /**
     * Abandons audio focus after command listening is done.
     * If the command was "stop/pause", the handler already sent the media key,
     * so music won't resume even after focus is abandoned.
     */
    private fun abandonListeningAudioFocus() {
        val am = audioManager ?: return
        com.voxapps.commander.utils.AudioFocusHelper.abandonFocus(am, audioFocusRequest)
        audioFocusRequest = null
        Logger.log("Listening audio focus abandoned", TAG)
    }

    private var googleResultCallback: ((String) -> Unit)? = null

    fun init(
        context: Context,
        whisperCpp: WhisperCppSttEngine?,
        whisperApi: WhisperSttEngine?,
        google: GoogleSttEngine?,
        vosk: VoskSttEngine?,
        settingsRepo: SettingsRepository,
        appStateManager: AppStateManager
    ) {
        this.context = context.applicationContext
        this.whisperCppEngine = whisperCpp
        this.whisperApiEngine = whisperApi
        this.googleSttEngine = google
        this.voskSttEngine = vosk
        this.settingsRepo = settingsRepo
        this.appStateManager = appStateManager
        
        Logger.log("VoiceManager initialized", TAG)
        
        // Start reactive observation of processor changes
        startProcessorObservation()
    }

    /**
     * Reactively observes the AppStateManager. When the user changes the processor,
     * this manager automatically cleans up and re-initializes engines.
     */
    private fun startProcessorObservation() {
        stateObservationJob?.cancel()
        val hub = appStateManager ?: return
        
        stateObservationJob = scope.launch {
            hub.uiState
                .map {
                    Triple(it.voiceProcessor, it.modelFilterLang, it.activeVoiceModelId) to
                    Pair(it.activeVoiceModelId, it.customWhisperModelPath)
                }
                .distinctUntilChanged()
                .collectLatest {
                    val uiState = hub.uiState.value
                    Logger.log("Engine-related change detected: ${uiState.voiceProcessor}. Updating engines...", TAG)
                    reinitializeEngines(uiState.voiceProcessor)
                }
        }
    }

    private suspend fun reinitializeEngines(processor: String) = withContext(Dispatchers.Main) {
        val hub = appStateManager ?: return@withContext
        val settings = settingsRepo ?: return@withContext
        val ctx = context ?: return@withContext

        // 1. Enter CLEANING state
        hub.setVoiceState(VoiceState.CLEANING)
        
        // 2. RELEASE all current hardware and resources
        release()
        
        // 3. RE-INITIALIZE based on new selection
        val snapshot = settings.getSettingsSnapshot()
        val apiKey = snapshot.apiKey
        val voiceLang = snapshot.voiceLanguage
        
        whisperCppEngine = WhisperCppSttEngine(
            ctx, 
            settings, 
            forceGpu = (processor == Strings.Processors.WHISPER_VULKAN)
        )
        
        whisperApiEngine = if (!apiKey.isNullOrBlank()) WhisperSttEngine(apiKey) else null
        googleSttEngine = GoogleSttEngine(ctx)
        voskSttEngine = VoskSttEngine(ctx, settings, voiceLang)
        
        // 4. Return to IDLE state
        hub.setVoiceState(VoiceState.IDLE)
        Logger.log("Engines updated successfully for $processor", TAG)
    }

    fun handleIntentResult(text: String) {
        isListeningFlag.set(false)
        _isListeningFlow.value = false
        abandonListeningAudioFocus()
        googleResultCallback?.invoke(text)
        googleResultCallback = null
        appStateManager?.setVoiceState(VoiceState.IDLE)
    }

    private fun startGoogleSpeechRecognizer(languageCode: String?) {
        val ctx = context ?: return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (languageCode != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Adjust silence timeouts based on STT sensitivity
                val (completeSilence, possiblyCompleteSilence, minLength) = when (cachedSttSensitivity) {
                    "low" -> Triple(1500L, 1500L, 3000L)   // cuts off faster — less sensitive
                    "high" -> Triple(5000L, 5000L, 8000L)  // waits longer — more sensitive
                    else -> Triple(3000L, 3000L, 5000L)    // medium
                }
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilence)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possiblyCompleteSilence)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minLength)
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // SpeechRecognizer returns dB (typically 0-10)
                    // Use calibrated noise floor if available, otherwise default based on sensitivity
                    val baseThreshold = when (cachedSttSensitivity) {
                        "low" -> 3f
                        "high" -> 1f
                        else -> 2f
                    }
                    val silenceThreshold = if (calibratedNoiseFloor > 0f) calibratedNoiseFloor * 20f else baseThreshold
                    val normalized = if (rmsdB < silenceThreshold) 0f
                        else ((rmsdB - silenceThreshold) / (10f - silenceThreshold)).coerceIn(0f, 1f)
                    _volumeFlow.value = normalized
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Logger.log("Google SpeechRecognizer error: $error", TAG)
                    isListeningFlag.set(false)
                    _isListeningFlow.value = false
                    abandonListeningAudioFocus()
                    googleResultCallback?.invoke("")
                    googleResultCallback = null
                    appStateManager?.setVoiceState(VoiceState.IDLE)
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Logger.log("Heard via SpeechRecognizer: $text", TAG)
                    isListeningFlag.set(false)
                    _isListeningFlow.value = false
                    abandonListeningAudioFocus()
                    googleResultCallback?.invoke(text)
                    googleResultCallback = null
                    appStateManager?.setVoiceState(VoiceState.IDLE)
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) _partialTranscriptionFlow.value = partial
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer?.startListening(intent)
            Logger.log("Google SpeechRecognizer started for language: ${languageCode ?: "auto-detect"}", TAG)
        } catch (e: Exception) {
            Logger.log("Failed to start Google SpeechRecognizer: ${e.message}", TAG)
            isListeningFlag.set(false)
            _isListeningFlow.value = false
            abandonListeningAudioFocus()
            googleResultCallback?.invoke("")
            googleResultCallback = null
            appStateManager?.setVoiceState(VoiceState.IDLE)
        }
    }

    fun setOfflineFallbackSettings(timeout: Int, model: String) {
        // Update settings
    }

    fun release() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        // Use the new common release interface for all engines
        whisperCppEngine?.release()
        whisperCppEngine = null
        
        whisperApiEngine?.release()
        whisperApiEngine = null
        
        googleSttEngine?.release()
        googleSttEngine = null
        
        voskSttEngine?.release()
        voskSttEngine = null
    }

    /**
     * Releases heavy native models (Whisper context, Vosk model, etc.) on system
     * memory pressure across all currently instantiated STT engines. Each engine
     * transparently reloads its resources on next use; guarded internally against
     * releasing mid-transcription.
     */
    fun releaseForMemoryPressure() {
        listOfNotNull(whisperCppEngine, whisperApiEngine, googleSttEngine, voskSttEngine)
            .forEach { it.releaseForMemoryPressure() }
    }

    private fun selectEngine(userPreference: String): SttEngine? {
        Logger.log("Selecting engine for preference: $userPreference", TAG)
        
        val selectedEngine = when (userPreference) {
            Strings.Processors.WHISPER_API -> {
                whisperApiEngine ?: whisperCppEngine ?: googleSttEngine
            }
            Strings.Processors.GOOGLE -> {
                googleSttEngine ?: whisperCppEngine
            }
            Strings.Processors.WHISPER_VULKAN -> {
                whisperCppEngine ?: googleSttEngine
            }
            else -> {
                // JSON-defined engines — route by extension
                val ext = com.voxapps.commander.data.remote.RemoteModelRegistry.getExtension(userPreference)
                when (ext) {
                    ".zip" -> voskSttEngine ?: whisperCppEngine ?: googleSttEngine
                    ".bin" -> whisperCppEngine ?: googleSttEngine
                    else -> whisperCppEngine ?: googleSttEngine
                }
            }
        }

        Logger.log("VoiceManager: Selected engine: ${selectedEngine?.javaClass?.simpleName}")
        return selectedEngine
    }

    fun startListening(languageCode: String, processor: String, onResult: (String) -> Unit) {
        if (!isListeningFlag.compareAndSet(false, true)) {
            Logger.log("Already listening — ignoring duplicate startListening call", TAG)
            return
        }

        // If auto-detect is enabled AND engine is multilingual, pass null for auto-detection
        val autoDetect = appStateManager?.uiState?.value?.voiceLanguageAutoDetect == true
        val processorKey = processor
        val isMultilingual = com.voxapps.commander.data.remote.RemoteModelRegistry.isMultilingual(processorKey)
        val effectiveLangCode = if (autoDetect && isMultilingual) null else languageCode

        if (processor == Strings.Processors.GOOGLE) {
            googleResultCallback = onResult
            _isListeningFlow.value = true
            _partialTranscriptionFlow.value = ""
            appStateManager?.setVoiceState(VoiceState.LISTENING_COMMAND)
            loadCalibrationProfile()
            requestListeningAudioFocus()
            Logger.log("Google STT: isListeningFlow=${_isListeningFlow.value}, voiceState=LISTENING_COMMAND, autoDetect=$autoDetect, starting SpeechRecognizer", TAG)
            startGoogleSpeechRecognizer(effectiveLangCode)
            return
        }

        val engine = selectEngine(processor)
        if (engine == null) {
            Logger.log("No STT engine available", TAG)
            isListeningFlag.set(false)
            onResult("Error: No STT engine")
            return
        }

        if (context?.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Logger.log("RECORD_AUDIO permission not granted", TAG)
            isListeningFlag.set(false)
            onResult("Permission Error")
            return
        }

        _isListeningFlow.value = true
        _partialTranscriptionFlow.value = ""
        appStateManager?.setVoiceState(VoiceState.LISTENING_COMMAND)
        loadCalibrationProfile()
        requestListeningAudioFocus()

        scope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(currentQuality.sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT) * 2

                // Declared before acquisition so they survive the recording block below
                // (which now releases the AudioRecord in a finally) for transcription.
                val audioChunks = mutableListOf<ShortArray>() // Use chunks to avoid boxing into Short objects
                var totalShorts = 0
                var maxRmsDetected = 0f

                @Suppress("MissingPermission")
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, // Calibrated for STT, avoids aggressive MIC processing
                    currentQuality.sampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                // Guarantee the AudioRecord (and mic handle) is released even if the loop
                // throws or we return early — otherwise it leaks until GC.
                try {
                    if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                        Logger.log("AudioRecord failed to initialize", TAG)
                        withContext(Dispatchers.Main) {
                            onResult("Mic Error")
                            updateListeningState(false)
                        }
                        return@launch
                    }

                    audioRecord.startRecording()
                    val buffer = ShortArray(bufferSize / 2)
                    var lastVoiceTime = System.currentTimeMillis()

                    // Loop continues as long as isListeningFlag is true
                    while (isListeningFlag.get()) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val chunk = buffer.copyOfRange(0, read)
                            audioChunks.add(chunk)
                            totalShorts += read

                            val rms = calculateRms(buffer, read)
                            // Normalize using calibrated profile: noise floor -> 0, max rms -> 1
                            val normalizedVolume = if (calibratedMaxRms > calibratedNoiseFloor && calibratedMaxRms > 0f) {
                                ((rms - calibratedNoiseFloor) / (calibratedMaxRms - calibratedNoiseFloor)).coerceIn(0f, 1f)
                            } else {
                                rms
                            }
                            _volumeFlow.value = normalizedVolume
                            if (rms > maxRmsDetected) maxRmsDetected = rms

                            if (rms > getSilenceThreshold()) {
                                lastVoiceTime = System.currentTimeMillis()
                            } else if (System.currentTimeMillis() - lastVoiceTime > SILENCE_TIMEOUT_MS) {
                                Logger.log("Silence detected, stopping recording", TAG)
                                isListeningFlag.set(false)
                            }
                        } else if (read < 0) {
                            Logger.log("AudioRecord error: $read", TAG)
                            break
                        }
                    }
                } finally {
                    try { audioRecord.stop() } catch (_: Exception) {}
                    audioRecord.release()
                }

                // Finalize STT - ONLY if we actually heard something
                if (audioChunks.isNotEmpty() && maxRmsDetected > getSilenceThreshold()) {
                    withContext(Dispatchers.Main) { 
                        _partialTranscriptionFlow.value = "Transcribing..." 
                        _isListeningFlow.value = false 
                        abandonListeningAudioFocus()
                        appStateManager?.setVoiceState(VoiceState.PROCESSING)
                    }
                    
                    // Flatten chunks into a single ShortArray efficiently
                    val finalShortArray = ShortArray(totalShorts)
                    var offset = 0
                    for (chunk in audioChunks) {
                        System.arraycopy(chunk, 0, finalShortArray, offset, chunk.size)
                        offset += chunk.size
                    }

                    // Convert ShortArray to ByteArray with correct Little Endian order for native engines
                    val byteArray = ByteBuffer.allocate(finalShortArray.size * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .apply { asShortBuffer().put(finalShortArray) }
                        .array()

                    val result = appStateManager?.executeSecureVoiceAction {
                        // Pass language code to engine if it supports it
                        val rawResult = if (engine is WhisperSttEngine) {
                            engine.transcribeWithLanguage(byteArray, effectiveLangCode)
                        } else if (engine is WhisperCppSttEngine) {
                            engine.transcribeWithLanguage(byteArray, effectiveLangCode)
                        } else {
                            engine.transcribe(byteArray)
                        }
                        
                        // Clean up transcription to remove trailing noise/formatting that kills regex matches
                        rawResult.trim().lowercase().removeSuffix(".")
                    } ?: "Error: Sync failed"
                    
                    withContext(Dispatchers.Main) { 
                        onResult(result) 
                    }
                } else {
                    abandonListeningAudioFocus()
                    withContext(Dispatchers.Main) { onResult("") }
                }

            } catch (e: Exception) {
                Logger.log("Error during recording: ${e.message}", TAG)
                if (e !is CancellationException) {
                    withContext(Dispatchers.Main) { onResult("Error: ${e.message}") }
                }
            } finally {
                withContext(Dispatchers.Main) { 
                    if (appStateManager?.uiState?.value?.voiceState == VoiceState.PROCESSING) {
                        // Callback already set PROCESSING — don't override with IDLE
                        isListeningFlag.set(false)
                        _isListeningFlow.value = false
                        _volumeFlow.value = 0f
                    } else {
                        updateListeningState(false) 
                    }
                }
            }
        }
    }

    private fun updateListeningState(listening: Boolean) {
        isListeningFlag.set(listening)
        _isListeningFlow.value = listening
        if (!listening) {
            appStateManager?.setVoiceState(VoiceState.IDLE)
            _volumeFlow.value = 0f
        }
    }

    fun stopListening() {
        Logger.log("Manual stop requested", TAG)
        // Setting isListeningFlag to false will break the loop gracefully 
        isListeningFlag.set(false)
        // Stop Google SpeechRecognizer if active
        speechRecognizer?.let {
            it.stopListening()
            it.destroy()
        }
        speechRecognizer = null
        abandonListeningAudioFocus()
    }

    private fun loadCalibrationProfile() {
        // Refresh cached settings from uiState (no runBlocking)
        val ui = appStateManager?.uiState?.value
        if (ui != null) {
            cachedSttSensitivity = ui.sttSensitivity
            cachedWakeWordProfileJson = ui.wakeWordProfileJson
        }
        val profileJson = cachedWakeWordProfileJson
        val profile = profileJson?.let { WakeWordProfile.fromJson(it) }
        if (profile != null && profile.noiseFloorRms > 0f) {
            calibratedNoiseFloor = profile.noiseFloorRms
            calibratedMaxRms = if (profile.maxRms > profile.noiseFloorRms) profile.maxRms else profile.avgRms * 2f
            Logger.log("Calibration loaded: noiseFloor=${calibratedNoiseFloor}, maxRms=${calibratedMaxRms}", TAG)
        } else {
            calibratedNoiseFloor = 0f
            calibratedMaxRms = 0f
        }
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Float =
        com.voxapps.commander.utils.AudioConvert.calculateRms(buffer, length)
}
