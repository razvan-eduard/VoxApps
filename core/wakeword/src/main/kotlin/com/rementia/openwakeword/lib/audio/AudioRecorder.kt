package com.rementia.openwakeword.lib.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.voxapps.audio.AdaptiveNoiseGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import android.content.Context
import kotlin.coroutines.coroutineContext
import android.annotation.SuppressLint

/**
 * Handles audio recording from device microphone.
 * Emits audio buffers as a Flow for processing.
 */
internal class AudioRecorder(
    private val context: Context,
    // --- VoxCommander patch: RMS silence gate (battery) — start ---
    // Buffers whose (normalized) RMS energy falls below this floor are dropped here, at the
    // earliest possible point — before the short->float conversion/allocation and before anything
    // is emitted into the Flow — so WakeWordEngine's ONNX inference (mel-spectrogram + embedding +
    // classifier, the dominant CPU/battery cost of always-on wake word detection) never even sees
    // silence. 0f preserves upstream behavior (gate disabled). See WakeWordSensitivity in the
    // consuming app for how this is derived from the user's sensitivity setting.
    private val rmsGate: Float = 0f,
    // --- VoxCommander patch: RMS silence gate (battery) — end ---
    // --- VoxCommander patch: adaptive noise-floor margin (battery in noisy environments) — start ---
    // How far above the *live* ambient noise floor (not [rmsGate]'s fixed constant) a buffer's RMS
    // must sit before it counts as signal — see AdaptiveNoiseGate. [rmsGate] still sets the floor
    // this can never drop below, preserving quiet-room behavior exactly; this margin is what keeps
    // the gate actually closing in a sustained noisy room, where a fixed threshold stops helping at
    // all. See WakeWordSensitivity.noiseGateMargin() in the consuming app.
    private val noiseGateMargin: Float = 2.0f,
    // --- VoxCommander patch: adaptive noise-floor margin (battery in noisy environments) — end ---
    // --- VoxCommander patch: configurable audio source (AEC) — start ---
    // MIC preserves upstream behavior. The consuming app passes VOICE_COMMUNICATION when the
    // user's "Echo Cancellation" setting is on, which asks the platform to run its acoustic echo
    // canceler / noise suppressor on the captured signal — without it, wake-word detection scores
    // raw, un-cancelled audio, including the device's own speaker output (TTS/music), which is far
    // more likely to false-trigger on that content. See OpenWakeWordEngine.kt for how this mirrors
    // the same AEC toggle already wired into the app's other (Vosk/Porcupine) wake-word engines.
    private val audioSource: Int = MediaRecorder.AudioSource.MIC
    // --- VoxCommander patch: configurable audio source (AEC) — end ---
) {
    
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_IN_SHORTS = 1280
    }
    
    /**
     * Check if audio recording permission is granted.
     */
    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Start recording audio and emit buffers as Flow.
     * 
     * @return Flow of float arrays containing audio samples
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Flow<FloatArray> = flow {
        require(hasRecordPermission()) { "RECORD_AUDIO permission not granted" }
        
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE_IN_SHORTS * 2)
        
        val audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }
        
        val audioBuffer = ShortArray(BUFFER_SIZE_IN_SHORTS)
        // Seeded to "now", not 0L: this flow re-executes from scratch on every start()/re-arm (it's
        // a cold Flow), so a 0L seed made every session's lead-in silence fall outside the tail
        // window below (now - 0 is always huge) and get dropped outright — a real gap in the stream
        // that left AudioProcessor's continuous internal buffers either frozen on their construction-
        // time placeholder (first-ever start) or stale (later re-arms) until the first loud buffer
        // spliced discontinuously onto them. Seeding to "now" means the lead-in is treated as tail
        // period from the first buffer, so it gets zero-emitted (kept continuous) instead of dropped.
        var lastSpeechMs = android.os.SystemClock.elapsedRealtime()
        val TAIL_DURATION_MS = 1500L
        // Fresh per start()/re-arm, same reasoning as lastSpeechMs above — a stale rolling noise-
        // floor estimate from a previous session (a different room, a different time of day)
        // shouldn't bias this one.
        val noiseGate = AdaptiveNoiseGate(minThreshold = rmsGate, marginMultiplier = noiseGateMargin)

        try {
            audioRecord.startRecording()

            while (coroutineContext.isActive) {
                val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)

                if (readCount > 0) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    // --- VoxCommander patch: RMS silence gate (battery) — start ---
                    if (rmsGate > 0f) {
                        var energy = 0f
                        for (i in 0 until readCount) {
                            val normalized = audioBuffer[i] / 32768.0f
                            energy += normalized * normalized
                        }
                        val rms = kotlin.math.sqrt(energy.toDouble() / readCount).toFloat()
                        val effectiveThreshold = noiseGate.effectiveThreshold(rms, now)

                        // Log RMS occasionally (approx every 1s of audio) to see levels
                        if (java.util.Random().nextInt(100) == 0) {
                            android.util.Log.v("AudioRecorder", "RMS: ${String.format(java.util.Locale.US, "%.6f", rms)} (Gate: $rmsGate, Effective: ${String.format(java.util.Locale.US, "%.6f", effectiveThreshold)})")
                        }

                        if (rms >= effectiveThreshold) {
                            lastSpeechMs = now
                        } else {
                            // Current buffer is silent. Should we drop it or emit zeros (tail)?
                            val inTailPeriod = (now - lastSpeechMs) < TAIL_DURATION_MS
                            if (inTailPeriod) {
                                // Emit zeros to keep the AI window moving for 1.5s after speech ends.
                                // This prevents "trapping" the wake word in the AI's incomplete memory.
                                emit(FloatArray(readCount) { 0f })
                                continue
                            } else {
                                // Sustained silence: drop entirely to save battery.
                                continue
                            }
                        }
                    }
                    // --- VoxCommander patch: RMS silence gate (battery) — end ---

                    // Convert short array to float array (normalize to -1.0 to 1.0)
                    val floatBuffer = FloatArray(readCount) { i ->
                        audioBuffer[i] / 32768.0f
                    }
                    emit(floatBuffer)
                }
            }
        } finally {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
        }
    }.flowOn(Dispatchers.IO)
}