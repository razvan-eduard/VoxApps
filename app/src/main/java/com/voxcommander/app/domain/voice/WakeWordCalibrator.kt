package com.voxcommander.app.domain.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voxcommander.app.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Calibrates a personalized VAD threshold by recording the user saying
 * the wake word 5 times at varying volumes. Analyzes voice-band RMS
 * to determine the optimal silence/voice threshold.
 */
class WakeWordCalibrator(
    private val context: Context,
    private val onProgress: (CalibrationState) -> Unit
) {
    companion object {
        private const val TAG = "WakeWordCalibrator"
        private const val SAMPLE_RATE = 16000
        private const val ROUNDS = 5
        private const val RECORDING_DURATION_MS = 3000L
        private const val SILENCE_RMS_DEFAULT = 0.008f
        private val VOICE_BAND = Pair(300f, 3400f)
    }

    sealed class CalibrationState {
        object Idle : CalibrationState()
        data class MeasuringNoise(val instruction: String) : CalibrationState()
        data class Waiting(val round: Int, val total: Int, val instruction: String) : CalibrationState()
        data class Listening(val round: Int, val total: Int) : CalibrationState()
        data class Analyzing(val round: Int) : CalibrationState()
        data class Complete(val profile: WakeWordProfile) : CalibrationState()
        data class Failed(val message: String) : CalibrationState()
    }

    private data class RoundResult(val rms: Float, val audioSamples: ShortArray)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false
    private var isCancelled = false

    private val _state = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val state = _state.asStateFlow()

    private val _volumeFlow = MutableStateFlow(0f)
    val volumeFlow = _volumeFlow.asStateFlow()

    fun startCalibration() {
        if (isRunning) return
        isRunning = true
        isCancelled = false

        scope.launch {
            try {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    _state.value = CalibrationState.Failed("RECORD_AUDIO permission not granted")
                    isRunning = false
                    return@launch
                }

                val allRmsValues = mutableListOf<Float>()
                val allVoicePrints = mutableListOf<FloatArray>()
                val allTemplates = mutableListOf<Array<FloatArray>>()

                // --- Phase 0: Measure background noise (2 seconds of silence) ---
                _state.value = CalibrationState.MeasuringNoise("Please stay quiet — measuring background noise...")
                onProgress(_state.value)
                val measuredNoiseRms = measureBackgroundNoise()
                Logger.log("Measured background noise RMS: $measuredNoiseRms", TAG)

                val instructions = listOf(
                    "Say your wake word at normal volume",
                    "Say your wake word louder",
                    "Say your wake word quieter",
                    "Say your wake word at normal volume",
                    "Say your wake word whispering"
                )

                for (round in 0 until ROUNDS) {
                    if (isCancelled) break

                    // Show instruction and wait for user to tap "Ready"
                    _state.value = CalibrationState.Waiting(round + 1, ROUNDS, instructions[round])
                    onProgress(_state.value)
                    waitForReadySignal(round + 1)
                    if (isCancelled) break

                    // Now listen for voice input
                    _state.value = CalibrationState.Listening(round + 1, ROUNDS)
                    onProgress(_state.value)

                    val result = recordAndAnalyze()
                    if (isCancelled) break

                    // Recording finished — now analyzing
                    _state.value = CalibrationState.Analyzing(round + 1)
                    onProgress(_state.value)

                    if (result != null && result.rms > 0f) {
                        allRmsValues.add(result.rms)
                        val voicePrint = VoiceFeatureExtractor.extract(result.audioSamples, result.audioSamples.size)
                        allVoicePrints.add(voicePrint)
                        val template = VoiceFeatureExtractor.extractSequence(result.audioSamples, result.audioSamples.size)
                        allTemplates.add(template)
                        Logger.log("Round ${round + 1}: RMS=${result.rms}, voicePrint + template (${template.size} frames) extracted", TAG)
                    } else {
                        Logger.log("Round ${round + 1}: No voice detected, retrying same round", TAG)
                        _state.value = CalibrationState.Waiting(round + 1, ROUNDS, "No voice detected! ${instructions[round]}")
                        onProgress(_state.value)
                        waitForReadySignal(round + 1)
                        if (isCancelled) break

                        _state.value = CalibrationState.Listening(round + 1, ROUNDS)
                        onProgress(_state.value)

                        val retryResult = recordAndAnalyze()
                        _state.value = CalibrationState.Analyzing(round + 1)
                        onProgress(_state.value)

                        if (retryResult != null && retryResult.rms > 0f) {
                            allRmsValues.add(retryResult.rms)
                            val voicePrint = VoiceFeatureExtractor.extract(retryResult.audioSamples, retryResult.audioSamples.size)
                            allVoicePrints.add(voicePrint)
                            val template = VoiceFeatureExtractor.extractSequence(retryResult.audioSamples, retryResult.audioSamples.size)
                            allTemplates.add(template)
                            Logger.log("Round ${round + 1} (retry): RMS=${retryResult.rms}", TAG)
                        } else {
                            Logger.log("Round ${round + 1}: No voice detected after retry, skipping", TAG)
                        }
                    }

                    delay(500)
                }

                if (allRmsValues.isEmpty()) {
                    _state.value = CalibrationState.Failed("No voice detected in any round")
                    isRunning = false
                    return@launch
                }

                // Calculate profile statistics
                val minRms = allRmsValues.min()
                val maxRms = allRmsValues.max()
                val avgRms = allRmsValues.average().toFloat()

                // Set threshold between measured background noise and the quietest wake word
                // Use the midpoint between noise floor and quietest speech, but at least 1.5x the noise
                val noiseFloor = measuredNoiseRms.coerceAtLeast(SILENCE_RMS_DEFAULT)
                val threshold = (noiseFloor * 1.5f).coerceAtMost(minRms * 0.7f).coerceAtLeast(noiseFloor)

                val voicePrintVector = if (allVoicePrints.isNotEmpty()) {
                    VoiceFeatureExtractor.average(allVoicePrints)
                } else null
                val voicePrintStr = voicePrintVector?.let { VoiceFeatureExtractor.encodeVector(it) }

                // Build template sequence (medoid of all samples)
                val templateSeq = if (allTemplates.isNotEmpty()) {
                    VoiceFeatureExtractor.averageSequences(allTemplates)
                } else null
                val templateStr = templateSeq?.let { VoiceFeatureExtractor.encodeSequence(it) }

                val profile = WakeWordProfile(
                    rmsThreshold = threshold,
                    minRms = minRms,
                    maxRms = maxRms,
                    avgRms = avgRms,
                    peakFreqLow = VOICE_BAND.first,
                    peakFreqHigh = VOICE_BAND.second,
                    wakeWord = "",
                    calibrationDate = System.currentTimeMillis(),
                    voicePrint = voicePrintStr,
                    similarityThreshold = 0.65f,
                    wakeWordTemplate = templateStr,
                    templateThreshold = 0.45f,
                    noiseFloorRms = measuredNoiseRms
                )

                Logger.log("Calibration complete: threshold=${profile.rmsThreshold}, min=${profile.minRms}, max=${profile.maxRms}, avg=${profile.avgRms}", TAG)
                _state.value = CalibrationState.Complete(profile)
                onProgress(_state.value)

            } catch (e: Exception) {
                Logger.log("Calibration failed: ${e.message}", TAG)
                _state.value = CalibrationState.Failed(e.message ?: "Unknown error")
                onProgress(_state.value)
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        isCancelled = true
        _state.value = CalibrationState.Idle
    }

    private val readySignals = mutableMapOf<Int, CompletableDeferred<Unit>>()

    fun signalReady(round: Int) {
        readySignals[round]?.complete(Unit)
    }

    private suspend fun waitForReadySignal(round: Int) {
        val deferred = CompletableDeferred<Unit>()
        readySignals[round] = deferred
        deferred.await()
        readySignals.remove(round)
    }

    /**
     * Records 2 seconds of "silence" to measure the actual background noise floor.
     * Returns the average filtered RMS of the voice band during this period.
     */
    private suspend fun measureBackgroundNoise(): Float = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2

        @Suppress("MissingPermission")
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            return@withContext SILENCE_RMS_DEFAULT
        }

        val vadFilter = BandpassFilterHelper(SAMPLE_RATE.toFloat(), VOICE_BAND.first, VOICE_BAND.second)
        val buffer = ShortArray(bufferSize / 2)
        val filtered = FloatArray(bufferSize / 2)
        val rmsValues = mutableListOf<Float>()

        audioRecord.startRecording()

        val noiseStart = System.currentTimeMillis()
        val NOISE_DURATION_MS = 2000L

        while (System.currentTimeMillis() - noiseStart < NOISE_DURATION_MS && isRunning && !isCancelled) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                vadFilter.process(buffer, filtered, read)
                val rms = calculateFilteredRms(filtered, read)
                _volumeFlow.value = rms
                rmsValues.add(rms)
            }
        }

        audioRecord.stop()
        audioRecord.release()

        if (rmsValues.isEmpty()) SILENCE_RMS_DEFAULT
        else rmsValues.average().toFloat()
    }

    private suspend fun recordAndAnalyze(): RoundResult? = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2

        @Suppress("MissingPermission")
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            return@withContext null
        }

        val vadFilter = BandpassFilterHelper(SAMPLE_RATE.toFloat(), VOICE_BAND.first, VOICE_BAND.second)
        val buffer = ShortArray(bufferSize / 2)
        val filtered = FloatArray(bufferSize / 2)
        val rmsValues = mutableListOf<Float>()
        val allAudio = mutableListOf<Short>()

        audioRecord.startRecording()

        // Phase 1: Wait for voice to start (up to 10s timeout)
        val waitStart = System.currentTimeMillis()
        val WAIT_TIMEOUT_MS = 10000L
        var voiceDetected = false

        while (!voiceDetected && System.currentTimeMillis() - waitStart < WAIT_TIMEOUT_MS && isRunning && !isCancelled) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                vadFilter.process(buffer, filtered, read)
                val rms = calculateFilteredRms(filtered, read)
                _volumeFlow.value = rms
                if (rms > SILENCE_RMS_DEFAULT) {
                    voiceDetected = true
                    rmsValues.add(rms)
                    allAudio.addAll(buffer.toList().subList(0, read))
                }
            }
        }

        if (!voiceDetected) {
            audioRecord.stop()
            audioRecord.release()
            return@withContext null
        }

        // Phase 2: Record until voice ends (silence for ~1s after voice detected)
        val SILENCE_TIMEOUT_MS = 1000L
        val lastVoiceTime = System.currentTimeMillis()
        val recordStart = System.currentTimeMillis()
        val MAX_RECORD_MS = 5000L

        while (System.currentTimeMillis() - lastVoiceTime < SILENCE_TIMEOUT_MS &&
              System.currentTimeMillis() - recordStart < MAX_RECORD_MS &&
              isRunning && !isCancelled) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read > 0) {
                vadFilter.process(buffer, filtered, read)
                val rms = calculateFilteredRms(filtered, read)
                _volumeFlow.value = rms
                if (rms > SILENCE_RMS_DEFAULT) {
                    rmsValues.add(rms)
                    allAudio.addAll(buffer.toList().subList(0, read))
                }
            }
        }

        audioRecord.stop()
        audioRecord.release()
        _volumeFlow.value = 0f

        if (rmsValues.isEmpty()) return@withContext null

        rmsValues.sort()
        val medianRms = rmsValues[rmsValues.size / 2]
        val audioArray = allAudio.toShortArray()
        return@withContext RoundResult(medianRms, audioArray)
    }

    private fun calculateFilteredRms(filtered: FloatArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            sum += filtered[i].toDouble() * filtered[i]
        }
        return sqrt(sum / length).toFloat()
    }

    /**
     * Minimal biquad bandpass filter for calibration (same as WakeWordEngine's BandpassFilter).
     */
    private class BandpassFilterHelper(sampleRate: Float, lowFreq: Float, highFreq: Float) {
        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f
        private val a1: Float
        private val a2: Float
        private val b0: Float
        private val b1: Float
        private val b2: Float

        init {
            val w1 = 2.0 * Math.PI * lowFreq / sampleRate
            val w2 = 2.0 * Math.PI * highFreq / sampleRate
            val k1 = Math.tan(w1 / 2)
            val k2 = Math.tan(w2 / 2)
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
    }
}
