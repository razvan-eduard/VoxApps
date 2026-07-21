package com.voxapps.commander.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.voxapps.audio.AdaptiveNoiseGate
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.voice.VoiceFeatureExtractor
import com.voxapps.commander.domain.voice.WakeWordProfile
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.state.VoiceState
import com.voxapps.commander.utils.AppScope
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class WakeWordEngine(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val appStateManager: AppStateManager,
    private val onWakeWordDetected: () -> Unit
) : IWakeWordEngine {
    private val TAG = Strings.Tags.WAKE_WORD_ENGINE
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isListening = false
    @Volatile private var cachedWakeWord: String = ""

    // Stored for re-initialization after memory pressure release
    private var storedModelPath: String? = null
    private var storedWakeWord: String? = null
    @Volatile private var isReinitializing = false

    // Guards check-then-act sections in startListening() against concurrent calls
    private val startStopLock = Any()

    // Managed scope for listenLoop — isolates exceptions so a native/JSON error
    // doesn't propagate as an uncaught exception and crash the process.
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Logger.log("Uncaught exception in listen loop: ${e.message}", TAG)
        isListening = false
    }
    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2

    // --- VAD: Bandpass filter (300-3400 Hz voice band) + RMS ---
    // Biquad bandpass filter coefficients (Butterworth, order 2)
    // Designed for center=1850Hz, bandwidth=3100Hz at 16kHz sample rate
    private val vadFilter = com.voxapps.commander.utils.BandpassFilter(sampleRate.toFloat(), 300f, 3400f)
    private val DEFAULT_VOICE_RMS_THRESHOLD = 0.008f
    private var voiceRmsThreshold = DEFAULT_VOICE_RMS_THRESHOLD
    private var consecutiveSilentFrames = 0
    private val SILENT_FRAMES_BEFORE_SLEEP = 3

    // --- Voice verification: rolling buffer + voice print ---
    private var storedVoicePrint: FloatArray? = null
    private var similarityThreshold = 0.65f
    private val rollingAudioBuffer = ArrayDeque<Short>()
    private val ROLLING_BUFFER_MAX_SAMPLES = 16000 * 2 // ~2 seconds at 16kHz

    // --- Template matching (language-agnostic KWS) ---
    private var storedTemplate: Array<FloatArray>? = null
    private var templateThreshold = 0.45f
    private var useTemplateMode = false
    private val voiceSegmentBuffer = ArrayDeque<Short>()
    private val SEGMENT_MAX_SAMPLES = 16000 * 3 // Max 3s segment for DTW
    private var isCollectingVoice = false
    private val SILENCE_FRAMES_TO_END_SEGMENT = 8

    override suspend fun initialize(modelPath: String, wakeWord: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.log("Init model: $modelPath", TAG)

            storedModelPath = modelPath
            storedWakeWord = wakeWord

            appStateManager.executeSecureVoiceAction {
                recognizer?.close()
                model?.close()
                recognizer = null
                model = null
            }

            val dir = File(modelPath)
            val actualPath = if (File(dir, "am").exists()) dir.absolutePath
            else dir.listFiles()?.find { File(it, "am").exists() }?.absolutePath ?: modelPath

            val newModel = Model(actualPath)

            val wakeWordClean = wakeWord.lowercase().trim()
            val individualWords = wakeWordClean.split(Regex("\\s+"))
            val vocab = mutableSetOf<String>().apply {
                addAll(individualWords)
                addAll(listOf("hey", "vox", "wake", "up", "commander", "[unknown]"))
            }
            val grammarJson = vocab.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"$it\"" }

            appStateManager.executeSecureVoiceAction {
                model = newModel
                recognizer = Recognizer(newModel, sampleRate.toFloat(), grammarJson)
                recognizer?.setWords(true)
            }
            return@withContext true
        } catch (e: Exception) {
            Logger.log("Init failed: ${e.message}", TAG)
            return@withContext false
        }
    }

    override fun startListening(): Boolean {
        // Load calibrated threshold if available
        val profileJson = settingsRepo.getWakeWordProfileJson()
        val profile = profileJson?.let { WakeWordProfile.fromJson(it) }
        if (profile != null) {
            val noiseFloor = if (profile.noiseFloorRms > 0f) profile.noiseFloorRms else DEFAULT_VOICE_RMS_THRESHOLD
            voiceRmsThreshold = profile.rmsThreshold.coerceAtLeast(noiseFloor)
            storedVoicePrint = VoiceFeatureExtractor.decodeVector(profile.voicePrint)
            similarityThreshold = profile.similarityThreshold
            storedTemplate = VoiceFeatureExtractor.decodeSequence(profile.wakeWordTemplate)
            val sensitivity = appStateManager.uiState.value.wakeWordSensitivity
            val sensitivityThreshold = WakeWordSensitivity.voskTemplateThreshold(sensitivity)
            templateThreshold = profile.templateThreshold.coerceAtMost(sensitivityThreshold)
            useTemplateMode = storedTemplate != null
            Logger.log("Calibrated: threshold=$voiceRmsThreshold, voicePrint=${if (storedVoicePrint != null) "yes" else "no"}, templateMode=$useTemplateMode, templateThreshold=$templateThreshold", TAG)
        } else {
            voiceRmsThreshold = DEFAULT_VOICE_RMS_THRESHOLD
            storedVoicePrint = null
            similarityThreshold = 0.65f
            storedTemplate = null
            templateThreshold = 0.45f
            useTemplateMode = false
            Logger.log("Using default VAD threshold: $voiceRmsThreshold (Vosk mode)", TAG)
        }

        // Cache wake word from uiState (avoids runBlocking per partial result)
        cachedWakeWord = appStateManager.uiState.value.wakeWord

        synchronized(startStopLock) {
        if (isListening) return true

        // Reset filter state and buffers to avoid stale data from previous session.
        // Inside the lock: listenLoop may still be draining these buffers — clearing
        // them concurrently would corrupt the non-thread-safe ArrayDeques.
        vadFilter.reset()
        rollingAudioBuffer.clear()
        voiceSegmentBuffer.clear()
        isCollectingVoice = false
        consecutiveSilentFrames = 0

        // Ensure any previous AudioRecord is fully released before creating a new one
        try {
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Logger.log("Error releasing previous AudioRecord: ${e.message}", TAG)
        }

        // If model was released due to memory pressure, re-initialize before listening.
        // IMPORTANT: startListening() is called synchronously from WakeWordService's
        // Dispatchers.Main serviceScope. A runBlocking{} here would freeze the Main
        // thread for the duration of the (potentially multi-second) model load —
        // an ANR risk for large models. Instead, kick off the reinit in the background
        // and return false immediately; WakeWordService already retries startListening()
        // after a delay, which will succeed once the background reinit completes.
        if (recognizer == null || model == null) {
            val path = storedModelPath
            val word = storedWakeWord
            if (path != null && word != null) {
                if (!isReinitializing) {
                    isReinitializing = true
                    Logger.log("Model was released (memory pressure) — reinitializing in background", TAG)
                    AppScope.io.launch {
                        val ok = try { initialize(path, word) } finally { isReinitializing = false }
                        Logger.log("Background model reinit ${if (ok) "succeeded" else "failed"}", TAG)
                    }
                }
                return false
            } else {
                Logger.log("Cannot start listening: recognizer or model is null and no stored path for re-init", TAG)
                return false
            }
        }

        try {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Logger.log("RECORD_AUDIO permission not granted", TAG)
                return false
            }

            // Reset recognizer state to clear any buffered audio from previous session
            try {
                recognizer?.reset()
            } catch (e: Exception) {
                Logger.log("Error resetting recognizer before listen: ${e.message}", TAG)
            }

            val aecEnabled = appStateManager.uiState.value.wakeWordAecEnabled
            val audioSource = if (aecEnabled) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            audioRecord = AudioRecord(audioSource, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.log("AudioRecord failed to initialize (state=${audioRecord?.state})", TAG)
                audioRecord?.release()
                audioRecord = null
                return false
            }

            isListening = true
            appStateManager.setWakeWordServiceListening(true)
            appStateManager.setVoiceState(VoiceState.LISTENING_WAKEWORD)
            audioRecord?.startRecording()

            if (!engineScope.isActive) engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
            engineScope.launch { listenLoop() }
            Logger.log("WakeWordEngine started listening successfully", TAG)
            return true
        } catch (e: Exception) {
            Logger.log("Exception starting AudioRecord: ${e.message}", TAG)
            isListening = false
            audioRecord?.release()
            audioRecord = null
            return false
        }
        }
    }

    private suspend fun listenLoop() {
        val buffer = ByteArray(bufferSize)
        val shortBuffer = ShortArray(bufferSize / 2)
        val filteredBuffer = FloatArray(bufferSize / 2)
        var consecutiveErrors = 0 // Anti CPU Burn logic
        consecutiveSilentFrames = 0

        // Fresh per listenLoop() call (mirrors consecutiveSilentFrames' reset above) — a stale
        // rolling noise-floor estimate from a previous session shouldn't bias this one.
        // voiceRmsThreshold already folds in calibration (see startListening()); this gate handles
        // *live* drift above that floor for the rest of the session, so a sustained noisy room
        // doesn't leave the gate stuck open running full Vosk decode nonstop.
        val noiseGate = AdaptiveNoiseGate(
            minThreshold = voiceRmsThreshold,
            marginMultiplier = WakeWordSensitivity.noiseGateMargin(appStateManager.uiState.value.wakeWordSensitivity)
        )

        while (isListening) {
            val currentAudioRecord = audioRecord ?: break
            if (currentAudioRecord.state != AudioRecord.STATE_INITIALIZED) break

            val read = try {
                currentAudioRecord.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                -1
            }

            if (read > 0 && isListening) {
                consecutiveErrors = 0 // Reset errors on successful read

                // Little Endian conversion
                val samplesRead = read / 2
                for (i in 0 until samplesRead) {
                    shortBuffer[i] = ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xFF)).toShort()
                }

                // --- VAD: Bandpass filter + RMS on voice band ---
                vadFilter.process(shortBuffer, filteredBuffer, samplesRead)
                val voiceRms = calculateFilteredRms(filteredBuffer, samplesRead)
                val effectiveThreshold = noiseGate.effectiveThreshold(voiceRms, SystemClock.elapsedRealtime())

                if (voiceRms < effectiveThreshold) {
                    // Silence in voice band
                    consecutiveSilentFrames++

                    if (useTemplateMode && isCollectingVoice && consecutiveSilentFrames >= SILENCE_FRAMES_TO_END_SEGMENT) {
                        // Voice segment ended — run DTW template matching
                        isCollectingVoice = false
                        checkTemplateMatch()
                    }

                    if (consecutiveSilentFrames >= SILENT_FRAMES_BEFORE_SLEEP) {
                        delay(10) // Small sleep during sustained silence
                    }
                    continue
                }

                // Voice detected — reset silence counter
                consecutiveSilentFrames = 0

                if (useTemplateMode) {
                    // Template mode: collect audio for DTW, skip Vosk entirely
                    isCollectingVoice = true
                    for (i in 0 until samplesRead) {
                        voiceSegmentBuffer.addLast(shortBuffer[i])
                    }
                    while (voiceSegmentBuffer.size > SEGMENT_MAX_SAMPLES) {
                        voiceSegmentBuffer.removeFirst()
                    }
                } else {
                    // Vosk mode: rolling buffer + Vosk inference
                    for (i in 0 until samplesRead) {
                        rollingAudioBuffer.addLast(shortBuffer[i])
                    }
                    while (rollingAudioBuffer.size > ROLLING_BUFFER_MAX_SAMPLES) {
                        rollingAudioBuffer.removeFirst()
                    }

                    appStateManager.executeSecureVoiceAction {
                        if (isListening && recognizer != null) {
                            try {
                                if (recognizer?.acceptWaveForm(shortBuffer, read / 2) == true) {
                                    handleResult(recognizer?.result)
                                } else {
                                    handlePartial(recognizer?.partialResult)
                                }
                            } catch (e: Exception) {
                                Logger.log("Vosk recognizer error: ${e.message}", TAG)
                            }
                        }
                    }
                }
            } else if (read < 0) {
                consecutiveErrors++
                Logger.log("Audio read error count: $consecutiveErrors", TAG)
                if (consecutiveErrors > 5) {
                    Logger.log("Too many audio read errors. Aborting loop to prevent CPU burn.", TAG)
                    stopListening()
                    break
                }
                delay(50)
            } else if (!isListening) {
                break
            }
        }
        Logger.log("WakeWord loop exited cleanly", TAG)
    }

    private fun handleResult(json: String?) {
        val text = json?.let { JSONObject(it).optString("text", "") } ?: ""
        if (text.isNotBlank()) {
            Logger.log("WW Full: $text")
            if (isValidWakeWordMatch(text) && verifyVoicePrint()) {
                onWakeWordDetected()
            } else if (isValidWakeWordMatch(text) && !verifyVoicePrint()) {
                Logger.log("WW match rejected: voice print mismatch", TAG)
            }
        }
    }

    private fun handlePartial(json: String?) {
        val partial = json?.let { JSONObject(it).optString("partial", "") } ?: ""
        if (partial.isNotBlank()) {
            if (isValidWakeWordMatch(partial) && verifyVoicePrint()) {
                Logger.log("WW Partial Match: $partial")
                onWakeWordDetected()
                recognizer?.reset()
            } else if (isValidWakeWordMatch(partial) && !verifyVoicePrint()) {
                Logger.log("WW partial match rejected: voice print mismatch", TAG)
                recognizer?.reset()
            }
        }
    }

    private fun isValidWakeWordMatch(heardText: String): Boolean {
        val target = cachedWakeWord.lowercase().trim()
        val cleanHeard = heardText.lowercase()
            .replace("[unknown]", "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleanHeard.isBlank()) return false
        // Fuzzy match: accept if either string contains the other.
        // Vosk with a small grammar can produce slight variations (extra filler words).
        return cleanHeard.contains(target) || target.contains(cleanHeard)
    }

    /**
     * Verifies the triggering audio against the stored voice print.
     * Returns true if no voice print is stored (no calibration) or if similarity >= threshold.
     * Returns false if the audio doesn't match the user's voice profile.
     */
    private fun verifyVoicePrint(): Boolean {
        val print = storedVoicePrint ?: return true // No calibration — always accept
        if (rollingAudioBuffer.isEmpty()) return true

        val samples = rollingAudioBuffer.toShortArray()
        val livePrint = VoiceFeatureExtractor.extract(samples, samples.size)
        val similarity = VoiceFeatureExtractor.similarity(print, livePrint)

        Logger.log("Voice print similarity: $similarity (threshold=$similarityThreshold)", TAG)
        return similarity >= similarityThreshold
    }

    /**
     * Template matching: compares collected voice segment against stored wake word template.
     * Uses DTW on 8-band spectral feature sequences.
     * Language-agnostic — matches the sound pattern, not text.
     */
    private fun checkTemplateMatch() {
        val template = storedTemplate ?: return
        if (voiceSegmentBuffer.isEmpty()) return

        // Need at least 0.3s of audio to be a valid candidate
        if (voiceSegmentBuffer.size < 16000 * 0.3) {
            voiceSegmentBuffer.clear()
            return
        }

        val samples = voiceSegmentBuffer.toShortArray()
        voiceSegmentBuffer.clear()

        val liveSeq = VoiceFeatureExtractor.extractSequence(samples, samples.size)
        val sim = VoiceFeatureExtractor.sequenceSimilarity(template, liveSeq)

        Logger.log("Template DTW similarity: $sim (threshold=$templateThreshold, frames=${liveSeq.size})", TAG)

        if (sim >= templateThreshold) {
            Logger.log("WW template match! Triggering wake word", TAG)
            onWakeWordDetected()
        }
    }

    override fun stopListening(): Unit = synchronized(startStopLock) {
        if (!isListening) return@synchronized
        Logger.log("Pausing WakeWordEngine listening", TAG)

        isListening = false
        rollingAudioBuffer.clear()
        voiceSegmentBuffer.clear()
        isCollectingVoice = false

        try {
            // Reset Vosk recognizer to flush any buffered audio/results
            recognizer?.reset()
        } catch (e: Exception) {
            Logger.log("Error resetting recognizer: ${e.message}", TAG)
        }

        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Logger.log("Error stopping AudioRecord: ${e.message}", TAG)
        } finally {
            audioRecord = null
            // Keep audio focus — only abandon on stopService to avoid media resuming
        }
    }

    override fun stopService() {
        stopListening()
        appStateManager.setWakeWordServiceListening(false)
        appStateManager.setVoiceState(VoiceState.IDLE)
    }

    override fun release() {
        stopService()
        engineScope.cancel()

        AppScope.io.launch {
            appStateManager.executeSecureVoiceAction {
                Logger.log("Releasing native resources...", TAG)
                recognizer?.close()
                model?.close()
                recognizer = null
                model = null
            }
        }
    }

    override fun releaseForMemoryPressure() {
        // Only release when genuinely idle. `!isListening` alone is not enough: during a
        // command flow the service calls stopListening() (isListening=false) while voiceState
        // is PROCESSING/LISTENING_COMMAND — releasing there frees the recognizer mid-flight
        // and the async reinit can lose the next wake trigger.
        val voiceState = appStateManager.uiState.value.voiceState
        if (isListening) {
            Logger.log("Skipping memory pressure release — engine is actively listening", TAG)
            return
        }
        if (voiceState != VoiceState.IDLE) {
            Logger.log("Skipping memory pressure release — command flow in progress (state=$voiceState)", TAG)
            return
        }
        Logger.log("Releasing Vosk model for memory pressure (model=${storedModelPath})", TAG)
        AppScope.io.launch {
            appStateManager.executeSecureVoiceAction {
                try { recognizer?.close() } catch (_: Exception) {}
                try { model?.close() } catch (_: Exception) {}
                recognizer = null
                model = null
            }
        }
    }

    private fun calculateFilteredRms(filtered: FloatArray, length: Int): Float =
        com.voxapps.commander.utils.AudioConvert.calculateFilteredRms(filtered, length)
}