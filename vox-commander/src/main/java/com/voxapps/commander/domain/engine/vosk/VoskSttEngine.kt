package com.voxapps.commander.domain.engine.vosk

import android.content.Context
import com.voxapps.commander.domain.engine.BaseVoxEngine
import com.voxapps.commander.domain.engine.ModelSpec
import com.voxapps.commander.domain.engine.SttEngine
import com.voxapps.commander.utils.AudioConvert
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline STT through Vosk.
 *
 * The engine no longer looks for its model. It used to resolve one itself in three tiers — a custom
 * path, then the active model id, then any directory under the app's files whose name started with
 * `vosk-model-` and mentioned the language — and then walk down subdirectories hunting for `am` or
 * `conf`. That walk existed because the extraction layout was not guaranteed, and it had to agree
 * with the download validator's separate idea of the same thing. Both are now answered once, by the
 * engine's declared entry point in models.json, and the resolved directory arrives in [ModelSpec].
 */
class VoskSttEngine(
    private val context: Context
) : BaseVoxEngine(), SttEngine {

    override val engineKey: String = ENGINE_KEY

    private var model: Model? = null
    private var activeRecognizer: Recognizer? = null

    override suspend fun onLoad(spec: ModelSpec): Boolean = withContext(Dispatchers.IO) {
        val local = spec as? ModelSpec.LocalModel ?: run {
            Logger.log("Vosk needs a local model, got ${spec::class.simpleName}", TAG)
            return@withContext false
        }
        // Vosk's Model(path) takes the directory; the entry point already resolved to it.
        model = Model(local.entryPoint.absolutePath)
        Logger.log("Vosk model loaded: ${local.entryPoint.name}", TAG)
        true
    }

    override fun onUnload() {
        try { activeRecognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        activeRecognizer = null
        model = null
    }

    override suspend fun processChunk(audio: ByteArray): String? = withContext(Dispatchers.IO) {
        val currentModel = model ?: return@withContext null

        withModel {
            if (activeRecognizer == null) {
                activeRecognizer = Recognizer(currentModel, SAMPLE_RATE)
            }
            activeRecognizer?.let {
                val shorts = AudioConvert.byteArrayToShorts(audio)
                it.acceptWaveForm(shorts, shorts.size)
                try {
                    JSONObject(it.partialResult).optString(JSON_KEY_PARTIAL, "")
                } catch (e: Exception) {
                    ""
                }
            }
        }
    }

    override suspend fun transcribe(audio: ByteArray, langCode: String?): String = withContext(Dispatchers.IO) {
        val currentModel = model ?: return@withContext "Error: Vosk model not loaded."

        withModel {
            try {
                val recognizer = activeRecognizer ?: Recognizer(currentModel, SAMPLE_RATE)
                val shorts = AudioConvert.byteArrayToShorts(audio)
                recognizer.acceptWaveForm(shorts, shorts.size)
                JSONObject(recognizer.finalResult).optString(JSON_KEY_TEXT, "")
            } catch (e: Exception) {
                Logger.log("Vosk transcription failed: ${e.message}", TAG)
                "Error: Transcription failed - ${e.message}"
            } finally {
                try { activeRecognizer?.close() } catch (_: Exception) {}
                activeRecognizer = null
            }
        }
    }

    companion object {
        const val ENGINE_KEY = "wake_vosk"
        private const val TAG = "VoskSttEngine"
        private const val JSON_KEY_TEXT = Strings.Vosk.JSON_KEY_TEXT
        private const val JSON_KEY_PARTIAL = Strings.Vosk.JSON_KEY_PARTIAL
        private const val SAMPLE_RATE = 16000.0f
    }
}
