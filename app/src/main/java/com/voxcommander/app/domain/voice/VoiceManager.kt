package com.voxcommander.app.domain.voice

import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.engine.SttEngine
import com.voxcommander.app.domain.engine.whisper.WhisperCppSttEngine
import com.voxcommander.app.domain.engine.google.GoogleSttEngine
import com.voxcommander.app.domain.engine.vosk.VoskSttEngine
import com.voxcommander.app.domain.engine.whisper.WhisperSttEngine
import com.voxcommander.app.domain.voice.WakeWordProfile
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.state.VoiceState
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

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

    @Volatile
    private var isListening = false
    private val _isListeningFlow = MutableStateFlow(false)
    val isListeningFlow = _isListeningFlow.asStateFlow()

    private var settingsRepo: SettingsRepository? = null
    private var appStateManager: AppStateManager? = null

    // Calibration values from WakeWordProfile for volume normalization
    private var calibratedNoiseFloor = 0f
    private var calibratedMaxRms = 0f

    private val _volumeFlow = MutableStateFlow(0f)
    val volumeFlow: StateFlow<Float> = _volumeFlow.asStateFlow()

    private val _partialTranscriptionFlow = MutableStateFlow("")
    val partialTranscriptionFlow: StateFlow<String> = _partialTranscriptionFlow.asStateFlow()

    private var launchGoogleIntentCallback: ((String) -> Unit)? = null
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
    private const val SILENCE_THRESHOLD = 0.02f
    private const val SILENCE_TIMEOUT_MS = 2000L

    private var googleResultCallback: ((String) -> Unit)? = null

    fun init(
        context: Context,
        whisperCpp: WhisperCppSttEngine?,
        whisperApi: WhisperSttEngine?,
        google: GoogleSttEngine?,
        vosk: VoskSttEngine?,
        @Suppress("UNUSED_PARAMETER") launchGoogleIntent: (String) -> Unit,
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
        _isListeningFlow.value = false
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
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // SpeechRecognizer returns dB (typically 0-10)
                    // Use calibrated noise floor if available, otherwise default threshold
                    val silenceThreshold = if (calibratedNoiseFloor > 0f) calibratedNoiseFloor * 20f else 2f
                    val normalized = if (rmsdB < silenceThreshold) 0f
                        else ((rmsdB - silenceThreshold) / (10f - silenceThreshold)).coerceIn(0f, 1f)
                    _volumeFlow.value = normalized
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Logger.log("Google SpeechRecognizer error: $error", TAG)
                    _isListeningFlow.value = false
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
                    _isListeningFlow.value = false
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
            _isListeningFlow.value = false
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
                val ext = com.voxcommander.app.data.remote.RemoteModelRegistry.getExtension(userPreference)
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
        if (isListening) return

        // If auto-detect is enabled AND engine is multilingual, pass null for auto-detection
        val autoDetect = appStateManager?.uiState?.value?.voiceLanguageAutoDetect == true
        val processorKey = processor
        val isMultilingual = com.voxcommander.app.data.remote.RemoteModelRegistry.isMultilingual(processorKey)
        val effectiveLangCode = if (autoDetect && isMultilingual) null else languageCode

        if (processor == Strings.Processors.GOOGLE) {
            googleResultCallback = onResult
            _isListeningFlow.value = true
            _partialTranscriptionFlow.value = ""
            appStateManager?.setVoiceState(VoiceState.LISTENING_COMMAND)
            loadCalibrationProfile()
            Logger.log("Google STT: isListeningFlow=${_isListeningFlow.value}, voiceState=LISTENING_COMMAND, autoDetect=$autoDetect, starting SpeechRecognizer", TAG)
            startGoogleSpeechRecognizer(effectiveLangCode)
            return
        }

        val engine = selectEngine(processor)
        if (engine == null) {
            Logger.log("No STT engine available", TAG)
            onResult("Error: No STT engine")
            return
        }

        if (context?.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Logger.log("RECORD_AUDIO permission not granted", TAG)
            onResult("Permission Error")
            return
        }

        isListening = true
        _isListeningFlow.value = true
        _partialTranscriptionFlow.value = ""
        appStateManager?.setVoiceState(VoiceState.LISTENING_COMMAND)
        loadCalibrationProfile()

        scope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(currentQuality.sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT) * 2
                
                @Suppress("MissingPermission")
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, // Calibrated for STT, avoids aggressive MIC processing
                    currentQuality.sampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Logger.log("AudioRecord failed to initialize", TAG)
                    withContext(Dispatchers.Main) {
                        onResult("Mic Error")
                        updateListeningState(false)
                    }
                    return@launch
                }

                audioRecord.startRecording()
                val audioChunks = mutableListOf<ShortArray>() // Use chunks to avoid boxing into Short objects
                val buffer = ShortArray(bufferSize / 2)
                var totalShorts = 0
                var lastVoiceTime = System.currentTimeMillis()
                var maxRmsDetected = 0f

                // Loop continues as long as isListening is true
                while (isListening) {
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
                        
                        if (rms > SILENCE_THRESHOLD) {
                            lastVoiceTime = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - lastVoiceTime > SILENCE_TIMEOUT_MS) {
                            Logger.log("Silence detected, stopping recording", TAG)
                            isListening = false 
                        }
                    } else if (read < 0) {
                        Logger.log("AudioRecord error: $read", TAG)
                        break
                    }
                }

                audioRecord.stop()
                audioRecord.release()

                // Finalize STT - ONLY if we actually heard something
                if (audioChunks.isNotEmpty() && maxRmsDetected > SILENCE_THRESHOLD) {
                    withContext(Dispatchers.Main) { 
                        _partialTranscriptionFlow.value = "Transcribing..." 
                        _isListeningFlow.value = false 
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
                        isListening = false
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
        isListening = listening
        _isListeningFlow.value = listening
        if (!listening) {
            appStateManager?.setVoiceState(VoiceState.IDLE)
            _volumeFlow.value = 0f
        }
    }

    fun stopListening() {
        Logger.log("Manual stop requested", TAG)
        // Setting isListening to false will break the loop gracefully 
        isListening = false
        // Stop Google SpeechRecognizer if active
        speechRecognizer?.let {
            it.stopListening()
            it.destroy()
        }
        speechRecognizer = null
    }

    private fun loadCalibrationProfile() {
        val profileJson = settingsRepo?.getWakeWordProfileJson()
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

    private fun calculateRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i]
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / length).toFloat() / 32768.0f
    }
}
