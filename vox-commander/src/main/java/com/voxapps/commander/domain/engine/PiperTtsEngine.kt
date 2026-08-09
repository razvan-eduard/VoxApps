package com.voxapps.commander.domain.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.voxapps.commander.utils.AppScope
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.utils.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Piper TTS engine using sherpa-onnx OfflineTts with VITS Piper voice models.
 *
 * Models are downloaded on-demand via ModelDownloader and stored as directories
 * containing model.onnx, tokens.txt, espeak-ng-data/, and optionally lexicon.txt.
 *
 * Audio is generated offline as FloatArray samples and played via AudioTrack.
 * Sentence-by-sentence generation for responsive stop and dynamic speech rate.
 */
class PiperTtsEngine : BaseVoxEngine(), ITtsEngine {

    override val engineKey: String = ENGINE_KEY

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f

    private var speakJob: Job? = null
    private var stopped = false

    /**
     * Builds the sherpa-onnx model from an already-resolved entry point.
     *
     * The engine no longer looks for anything: it is handed the `.onnx` weights that
     * `ModelDownloader.resolveEntryPoint` resolved from the engine's declared entry point in
     * models.json, and reads its siblings from the same directory. It used to scan the app's files
     * directory for `vits-piper-*` names and pick by quality suffix — a heuristic that had to agree
     * with the download validator's own, separate idea of the layout, and did not.
     */
    override suspend fun onLoad(spec: ModelSpec): Boolean {
        val local = spec as? ModelSpec.LocalModel ?: run {
            Logger.log("Piper needs a local model, got ${spec::class.simpleName}", TAG)
            return false
        }

        val modelFile = local.entryPoint
        val voiceDir = modelFile.parentFile ?: return false
        val tokensFile = File(voiceDir, "tokens.txt")
        val espeakDir = File(voiceDir, "espeak-ng-data")
        val lexiconFile = File(voiceDir, "lexicon.txt")

        if (!modelFile.exists() || !tokensFile.exists()) {
            Logger.log("Missing .onnx weights or tokens.txt in $voiceDir", TAG)
            return false
        }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    tokens = tokensFile.absolutePath,
                    dataDir = if (espeakDir.exists()) espeakDir.absolutePath else "",
                    lexicon = if (lexiconFile.exists()) lexiconFile.absolutePath else "",
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )

        tts = OfflineTts(config = config)
        Logger.log("Piper TTS loaded: ${voiceDir.name}, sampleRate=${tts?.sampleRate()}, lang=${local.language}", TAG)
        return true
    }

    override fun onUnload() {
        try { tts?.release() } catch (_: Exception) {}
        tts = null
    }

    override fun onRelease() {
        stop()
    }

    override fun speak(text: String, utteranceId: String?, onDone: (() -> Unit)?) {
        // Readiness is the caller's responsibility now — TtsManager loads before speaking. The
        // engine used to reload itself here, which meant a blocking model load on whatever thread
        // happened to ask for speech.
        val engine = tts ?: run {
            Logger.log("Piper TTS has no model loaded, cannot speak", TAG)
            onDone?.invoke()
            return
        }

        stopped = false
        speakJob = AppScope.io.launch {
            try {
                // Pins the model for the duration: a concurrent unload (memory pressure) defers
                // instead of releasing the native handle mid-generation.
                withModel {
                    for (chunk in TextUtils.splitSentences(text)) {
                        if (stopped) {
                            Logger.log("Piper TTS stopped mid-generation", TAG)
                            break
                        }

                        Logger.log("Generating audio for: ${chunk.take(60)}...", TAG)
                        val audio = engine.generate(text = chunk, sid = 0, speed = speechRate)

                        if (stopped) break

                        playSamples(audio.samples, audio.sampleRate)
                    }
                }
            } catch (e: Exception) {
                Logger.log("Piper TTS generation error: ${e.message}", TAG)
            } finally {
                stopAudioTrack()
                onDone?.invoke()
            }
        }
    }

    private suspend fun playSamples(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufSize = maxOf(minBufSize, samples.size * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack = track
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()

        // Wait for playback to complete
        val durationMs = (samples.size.toFloat() / sampleRate * 1000).toInt()
        kotlinx.coroutines.delay(durationMs.toLong())

        track.stop()
        track.release()
        audioTrack = null
    }

    private fun stopAudioTrack() {
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioTrack = null
    }

    override fun stop() {
        stopped = true
        speakJob?.cancel()
        speakJob = null
        stopAudioTrack()
        Logger.log("Piper TTS stopped", TAG)
    }

    override fun isSpeaking(): Boolean {
        return speakJob?.isActive == true
    }

    override fun setSpeechRate(rate: Float) {
        // Piper uses speed parameter: 1.0 = normal, 2.0 = 2x faster
        speechRate = rate
    }

    override fun setPitch(pitch: Float) {
        // VITS Piper doesn't support pitch adjustment directly
        // Store for interface compliance; could adjust sample rate as workaround
        this.pitch = pitch
    }

    companion object {
        const val ENGINE_KEY = "piper_tts"
        private const val TAG = "PiperTtsEngine"
    }
}
