package com.voxapps.commander.domain.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voxapps.commander.utils.BandpassFilter
import com.voxapps.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // SupervisorJob so the scope stays usable if one calibration run fails, and so [release] has a
    // job to cancel — without it a screen exit mid-calibration left the recording loop running with
    // the mic still open.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // @Volatile on both: written from the UI thread ([startCalibration]/[stop]) and read from the
    // recording loops on the IO dispatcher. Without the barrier the JIT is free to hoist these reads
    // out of the tight `while (... && isRunning && !isCancelled)` loops, so a stop() could never be
    // observed and the mic would keep recording until the round timed out on its own.
    @Volatile private var isRunning = false
    @Volatile private var isCancelled = false

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

    /** [stop] plus tearing down [scope] — call from the owning screen's `onDispose`. [stop] alone
     *  only asks the loops to exit; this also cancels the coroutine so nothing survives the screen
     *  that started it (this class is remembered per-composition, not an app-lifetime singleton). */
    fun release() {
        stop()
        scope.cancel()
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

        val vadFilter = BandpassFilter(SAMPLE_RATE.toFloat(), VOICE_BAND.first, VOICE_BAND.second)
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

        val vadFilter = BandpassFilter(SAMPLE_RATE.toFloat(), VOICE_BAND.first, VOICE_BAND.second)
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

    private fun calculateFilteredRms(filtered: FloatArray, length: Int): Float =
        com.voxapps.commander.utils.AudioConvert.calculateFilteredRms(filtered, length)

}
