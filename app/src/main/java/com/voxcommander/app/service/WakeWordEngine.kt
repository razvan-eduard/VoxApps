package com.voxcommander.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.voice.VoiceFeatureExtractor
import com.voxcommander.app.domain.voice.WakeWordProfile
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.state.VoiceState
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private var isListening = false

   private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2

    // --- VAD: Bandpass filter (300-3400 Hz voice band) + RMS ---
    // Biquad bandpass filter coefficients (Butterworth, order 2)
    // Designed for center=1850Hz, bandwidth=3100Hz at 16kHz sample rate
    private val vadFilter = BandpassFilter(sampleRate.toFloat(), 300f, 3400f)
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

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        Logger.log("Audio focus lost. Pausing listening.", TAG)
                        stopListening()
                    }
                }.build()

            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
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
            val sensitivity = settingsRepo.getSettingsSnapshot().wakeWordSensitivity
            val sensitivityThreshold = when (sensitivity) {
                "high" -> 0.35f
                "low" -> 0.55f
                else -> 0.45f // medium
            }
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

        // Reset filter state and buffers to avoid stale data from previous session
        vadFilter.reset()
        rollingAudioBuffer.clear()
        voiceSegmentBuffer.clear()
        isCollectingVoice = false
        consecutiveSilentFrames = 0

        if (isListening) return true
        try {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false
            }

            if (!requestAudioFocus()) {
                Logger.log("Could not gain audio focus. Cannot start listening.", TAG)
                return false
            }

            audioRecord?.release()
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                abandonAudioFocus()
                return false
            }

            isListening = true
            appStateManager.setWakeWordServiceListening(true)
            appStateManager.setVoiceState(VoiceState.LISTENING_WAKEWORD)
            audioRecord?.startRecording()

            CoroutineScope(Dispatchers.IO).launch { listenLoop() }
            return true
        } catch (e: Exception) {
            Logger.log("Exception starting AudioRecord: ${e.message}", TAG)
            isListening = false
            abandonAudioFocus()
            return false
        }
    }

    private suspend fun listenLoop() {
        val buffer = ByteArray(bufferSize)
        val shortBuffer = ShortArray(bufferSize / 2)
        val filteredBuffer = FloatArray(bufferSize / 2)
        var consecutiveErrors = 0 // Anti CPU Burn logic
        consecutiveSilentFrames = 0

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

                if (voiceRms < voiceRmsThreshold) {
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
        val target = settingsRepo.getSettingsSnapshot().wakeWord.lowercase().trim()
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

    override fun stopListening() {
        if (!isListening) return
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
            abandonAudioFocus()
        }
    }

    override fun stopService() {
        stopListening()
        appStateManager.setWakeWordServiceListening(false)
        appStateManager.setVoiceState(VoiceState.IDLE)
    }

    override fun release() {
        stopService()

        CoroutineScope(Dispatchers.IO).launch {
            appStateManager.executeSecureVoiceAction {
                Logger.log("Releasing native resources...", TAG)
                recognizer?.close()
                model?.close()
                recognizer = null
                model = null
            }
        }
    }

    private fun calculateFilteredRms(filtered: FloatArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            sum += filtered[i].toDouble() * filtered[i]
        }
        return kotlin.math.sqrt(sum / length).toFloat()
    }
}

/**
 * Second-order biquad bandpass filter (Butterworth).
 * Passes frequencies in [lowFreq, highFreq] range, attenuates everything else.
 * Used for voice activity detection — isolates the 300-3400 Hz voice band.
 */
private class BandpassFilter(sampleRate: Float, lowFreq: Float, highFreq: Float) {
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    // Biquad coefficients (cascade of two first-order sections for bandpass)
    private val a1: Float
    private val a2: Float
    private val b0: Float
    private val b1: Float
    private val b2: Float

    init {
        val w1 = 2.0 * Math.PI * lowFreq / sampleRate
        val w2 = 2.0 * Math.PI * highFreq / sampleRate

        // Pre-warp for bilinear transform
        val k1 = Math.tan(w1 / 2)
        val k2 = Math.tan(w2 / 2)

        // Bandpass via cascaded lowpass + highpass (Butterworth order 2)
        // Simplified biquad bandpass coefficients
        val bw = (k2 - k1).toFloat()
        val center = (k1 * k2).toFloat()

        val norm = 1f + bw + center

        b0 = bw / norm
        b1 = 0f
        b2 = -bw / norm
        a1 = (2f * (center - 1f)) / norm
        a2 = (1f - bw + center) / norm
    }

    fun process(input: ShortArray, output: FloatArray, length: Int) {
        for (i in 0 until length) {
            val x0 = input[i].toFloat() / 32768f
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

            output[i] = y0

            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
        }
    }

    fun reset() {
        x1 = 0f; x2 = 0f
        y1 = 0f; y2 = 0f
    }
}