package com.voxapps.commander.domain.engine.vosk

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.SttEngine
import com.voxapps.commander.utils.AudioConvert
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * A real implementation of the local offline STT engine using Vosk.
 */
class VoskSttEngine(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val langCode: String = DEFAULT_LANG
) : SttEngine {
    private var model: Model? = null
    private var activeRecognizer: Recognizer? = null
    @Volatile private var isTranscribing = false

    private suspend fun ensureModelLoaded() = withContext(Dispatchers.IO) {
        if (model == null) {
            try {
                val snapshot = settingsRepo.getSettingsSnapshot()
                val voskKey = com.voxapps.commander.data.remote.RemoteModelRegistry.getEngineKeyByExtension(".zip")
                val customPath: String? = voskKey?.let { snapshot.getCustomModelPath(it, langCode) }
                if (!customPath.isNullOrBlank() && File(customPath).exists()) {
                    val actualPath = findModelDir(File(customPath))?.absolutePath ?: customPath
                    model = Model(actualPath)
                    return@withContext
                }

                val rootDir = context.getExternalFilesDir(null)

                // TIER 1: Specific selected model
                val selectedModelName = snapshot.activeVoiceModelId
                val specificModelDir = if (!selectedModelName.isNullOrBlank()) {
                    File(rootDir, selectedModelName)
                } else null

                val modelDir = if (specificModelDir?.exists() == true) {
                    specificModelDir
                } else {
                    // TIER 2: Fallback to any model for this language
                    rootDir?.listFiles()?.find { 
                        it.isDirectory && it.name.startsWith(MODEL_DIR_PREFIX) && it.name.contains(langCode, ignoreCase = true) 
                    }
                }
                
                if (modelDir != null && modelDir.exists()) {
                    val actualPath = findModelDir(modelDir)?.absolutePath ?: modelDir.absolutePath
                    model = Model(actualPath)
                }
            } catch (e: Exception) {
                Logger.log("Vosk model load failed: ${e.message}", TAG)
            }
        }
    }

    /**
     * Walks down the first subdirectory at each level looking for the one holding the model files.
     *
     * Bounded on two axes, because the input is an archive the user downloaded and this unpacks
     * wherever its own directory layout leads. `tailrec` compiles the descent to a loop, so a deeply
     * nested archive can't overflow the stack; [MAX_MODEL_DIR_DEPTH] then stops a directory symlink
     * that points back at an ancestor, which the loop alone would follow forever. The previous form
     * had neither — and its recursive call sat inside a `let`, so it wasn't in tail position and
     * couldn't be marked `tailrec` without this restructuring.
     */
    private tailrec fun findModelDir(dir: File, depth: Int = 0): File? {
        if (File(dir, AM_FILE).exists() || File(dir, CONF_FILE).exists()) return dir
        if (depth >= MAX_MODEL_DIR_DEPTH) {
            Logger.log("Vosk model search gave up below $dir after $MAX_MODEL_DIR_DEPTH levels", TAG)
            return null
        }
        val next = dir.listFiles()?.firstOrNull { it.isDirectory } ?: return null
        return findModelDir(next, depth + 1)
    }

    override suspend fun processChunk(audio: ByteArray): String? = withContext(Dispatchers.IO) {
        ensureModelLoaded()
        val currentModel = model ?: return@withContext null
        
        if (activeRecognizer == null) {
            activeRecognizer = Recognizer(currentModel, SAMPLE_RATE)
        }
        
        isTranscribing = true
        try {
            activeRecognizer?.let {
                val shorts = AudioConvert.byteArrayToShorts(audio)
                it.acceptWaveForm(shorts, shorts.size)
                val partialJson = it.partialResult
                return@withContext try {
                    JSONObject(partialJson).optString(JSON_KEY_PARTIAL, "")
                } catch (e: Exception) {
                    ""
                }
            }
            return@withContext null
        } finally {
            isTranscribing = false
        }
    }

    override suspend fun transcribe(audio: ByteArray): String = withContext(Dispatchers.IO) {
        ensureModelLoaded()
        val currentModel = model ?: return@withContext "Error: Vosk Model ($langCode) not found."
        
        isTranscribing = true
        val result = try {
            // Reuse the active recognizer if available, otherwise create a fresh one
            val recognizer = activeRecognizer ?: Recognizer(currentModel, SAMPLE_RATE)
            val shorts = AudioConvert.byteArrayToShorts(audio)
            recognizer.acceptWaveForm(shorts, shorts.size)

            val resultJson = recognizer.finalResult
            JSONObject(resultJson).optString(JSON_KEY_TEXT, "")
        } catch (e: Exception) {
            Logger.log("Vosk transcription failed: ${e.message}", TAG)
            "Error: Transcription failed - ${e.message}"
        } finally {
            activeRecognizer?.close()
            activeRecognizer = null
            isTranscribing = false
        }
        return@withContext result
    }

    override fun releaseHardware() {
        try {
            activeRecognizer?.close()
            model?.close()
        } catch (e: Exception) {
            Logger.log("Vosk releaseHardware failed: ${e.message}", TAG)
        }
    }

    override fun releaseResources() {
        activeRecognizer = null
        model = null
    }

    /**
     * Releases the Vosk model on system memory pressure while keeping the engine
     * alive. ensureModelLoaded() will transparently reload it on the next use.
     * Skipped if a transcription is currently in progress.
     */
    override fun releaseForMemoryPressure() {
        if (isTranscribing) return
        if (model == null) return
        try {
            activeRecognizer?.close()
            model?.close()
        } catch (e: Exception) {
            Logger.log("Vosk releaseForMemoryPressure failed: ${e.message}", TAG)
        }
        activeRecognizer = null
        model = null
    }

    companion object {
        private const val TAG = "VoskSttEngine"
        private const val DEFAULT_LANG = Strings.Vosk.DEFAULT_LANG
        private const val MODEL_DIR_PREFIX = "vosk-model-"

        /** Depth cap for [findModelDir]. A real unpacked Vosk model nests two or three levels at
         *  most; this only has to be past that and short of looping on a symlink cycle. */
        private const val MAX_MODEL_DIR_DEPTH = 10
        private const val AM_FILE = Strings.Vosk.AM_FILE
        private const val CONF_FILE = Strings.Vosk.CONF_FILE
        private const val JSON_KEY_TEXT = Strings.Vosk.JSON_KEY_TEXT
        private const val JSON_KEY_PARTIAL = Strings.Vosk.JSON_KEY_PARTIAL
        private const val SAMPLE_RATE = 16000.0f
    }
}
