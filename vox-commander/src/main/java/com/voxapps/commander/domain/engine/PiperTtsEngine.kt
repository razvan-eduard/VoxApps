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
import com.voxapps.commander.utils.Logger
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
class PiperTtsEngine : ITtsEngine {

    companion object {
        private const val TAG = "PiperTtsEngine"
    }

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var ready = false
    private var modelDir: String = ""
    private var currentLanguage: String = "en"
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f

    private var speakJob: Job? = null
    private var stopped = false

    // Retained for lazy reload after releaseForMemoryPressure()
    private var storedContext: Context? = null
    @Volatile private var isSpeakingNow = false

    override fun initialize(context: Context, language: String): Boolean {
        storedContext = context.applicationContext
        currentLanguage = language
        ready = false

        // Release any existing instance
        release()

        // Resolve model directory for this language
        val rootDir = context.getExternalFilesDir(null) ?: run {
            Logger.log("No external files dir available", TAG)
            return false
        }

        // Piper voice directories are named like "vits-piper-en_US-amy-low"
        val langKey = language.substringBefore("_").lowercase()
        val voiceDir = findVoiceDir(rootDir, langKey)

        if (voiceDir == null || !voiceDir.exists()) {
            Logger.log("No Piper voice model found for language '$langKey' in $rootDir", TAG)
            return false
        }

        modelDir = voiceDir.absolutePath

        val modelFile = File(modelDir, "model.onnx")
        val tokensFile = File(modelDir, "tokens.txt")
        val espeakDir = File(modelDir, "espeak-ng-data")
        val lexiconFile = File(modelDir, "lexicon.txt")

        if (!modelFile.exists() || !tokensFile.exists()) {
            Logger.log("Missing model.onnx or tokens.txt in $modelDir", TAG)
            return false
        }

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = modelFile.absolutePath,
            tokens = tokensFile.absolutePath,
            dataDir = if (espeakDir.exists()) espeakDir.absolutePath else "",
            lexicon = if (lexiconFile.exists()) lexiconFile.absolutePath else "",
        )

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )

        return try {
            tts = OfflineTts(config = config)
            ready = true
            Logger.log("Piper TTS initialized: ${voiceDir.name}, sampleRate=${tts?.sampleRate()}, lang=$language", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Piper TTS init failed: ${e.message}", TAG)
            false
        }
    }

    override fun speak(text: String, utteranceId: String?, onDone: (() -> Unit)?) {
        if (!ready) {
            // Lazily reload if the model was released for memory pressure
            val ctx = storedContext
            if (ctx != null && !initialize(ctx, currentLanguage)) {
                Logger.log("Piper TTS not ready, cannot speak", TAG)
                onDone?.invoke()
                return
            } else if (ctx == null) {
                Logger.log("Piper TTS not ready, cannot speak", TAG)
                onDone?.invoke()
                return
            }
        }

        val engine = tts ?: run {
            onDone?.invoke()
            return
        }

        stopped = false
        isSpeakingNow = true
        speakJob = AppScope.io.launch {
            try {
                val chunks = TextUtils.splitSentences(text)

                for (chunk in chunks) {
                    if (stopped) {
                        Logger.log("Piper TTS stopped mid-generation", TAG)
                        break
                    }

                    Logger.log("Generating audio for: ${chunk.take(60)}...", TAG)
                    val audio = engine.generate(
                        text = chunk,
                        sid = 0,
                        speed = speechRate,
                    )

                    if (stopped) break

                    playSamples(audio.samples, audio.sampleRate)
                }
            } catch (e: Exception) {
                Logger.log("Piper TTS generation error: ${e.message}", TAG)
            } finally {
                stopAudioTrack()
                isSpeakingNow = false
                if (!stopped) {
                    onDone?.invoke()
                } else {
                    onDone?.invoke()
                }
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

    override fun release() {
        stop()
        try { tts?.release() } catch (_: Exception) {}
        tts = null
        ready = false
        storedContext = null
        Logger.log("Piper TTS released", TAG)
    }

    /**
     * Releases the sherpa-onnx model on system memory pressure while keeping the
     * engine usable — speak() will transparently reload it on the next call.
     * Skipped if currently speaking.
     */
    override fun releaseForMemoryPressure() {
        if (isSpeakingNow) {
            Logger.log("Skipping Piper release — actively speaking", TAG)
            return
        }
        if (tts == null) return
        Logger.log("Releasing Piper TTS model for memory pressure", TAG)
        try { tts?.release() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    /**
     * Finds a Piper voice directory matching the given language code.
     * Directory naming convention: vits-piper-{lang}_{country}-{voice}-{quality}
     */
    private fun findVoiceDir(rootDir: File, langKey: String): File? {
        val piperDirs = rootDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("vits-piper-") && file.name.contains("-$langKey-")
        }?.toList() ?: emptyList()

        // Also check for exact lang match like vits-piper-en_US-amy-low
        val exactMatch = rootDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("vits-piper-${langKey}_") ||
            (file.isDirectory && file.name.startsWith("vits-piper-") && file.name.contains("_${langKey}-"))
        }?.toList() ?: emptyList()

        val allMatches = (piperDirs + exactMatch).distinctBy { it.name }
        if (allMatches.isEmpty()) return null

        // Prefer "low" quality (smaller, faster) for on-device
        return allMatches.minByOrNull { it.name.contains("low") } ?: allMatches.first()
    }
}
