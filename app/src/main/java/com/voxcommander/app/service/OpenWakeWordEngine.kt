package com.voxcommander.app.service

import android.content.Context
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordDetection
import com.rementia.openwakeword.lib.model.WakeWordModel
import com.voxcommander.app.data.remote.RemoteModelRegistry
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.state.VoiceState
import com.voxcommander.app.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OpenWakeWordEngine(
    private val context: Context,
    private val appStateManager: AppStateManager,
    private val onWakeWordDetected: () -> Unit
) : IWakeWordEngine {

    private val TAG = "OpenWakeWordEngine"
    private var engine: WakeWordEngine? = null
    private var detectionJob: Job? = null
    private var isListening = false
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ENGINE_KEY = "wake_openwakeword"
    }

    override suspend fun initialize(modelPath: String, wakeWord: String): Boolean = withContext(Dispatchers.IO) {
        try {
            engine?.release()
            engine = null

            // modelPath is the model id from the registry (e.g. "alexa_v0.1.onnx")
            // Resolve the actual path from RemoteModelRegistry
            val modelId = modelPath.ifBlank { "alexa_v0.1.onnx" }
            val modelName = wakeWord.ifBlank { modelId.removeSuffix(".onnx") }

            // Look up the model in the registry
            val registryModel = RemoteModelRegistry.getModels(ENGINE_KEY).find { it.id == modelId }
            val resolvedPath = if (registryModel != null) {
                // If path is an absolute file path (custom model), use directly
                // If path is a relative asset path (e.g. "openwakeword/alexa_v0.1.onnx"), use as-is
                registryModel.url
            } else {
                // Fallback: check internal storage for custom model
                val customFile = File(context.filesDir, "openwakeword_models/$modelId")
                if (customFile.exists()) {
                    customFile.absolutePath
                } else {
                    // Fallback: try assets path
                    "openwakeword/$modelId"
                }
            }

            Logger.log("Initializing OpenWakeWord with model: $modelId (resolvedPath=$resolvedPath)", TAG)

            val models = listOf(
                WakeWordModel(
                    name = modelName,
                    modelPath = resolvedPath,
                    threshold = 0.5f
                )
            )

            engine = WakeWordEngine(
                context = context,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 2000L,
                scope = engineScope
            )

            Logger.log("OpenWakeWord engine initialized successfully", TAG)
            return@withContext true
        } catch (e: Exception) {
            Logger.log("OpenWakeWord init failed: ${e.message}", TAG)
            return@withContext false
        }
    }

    override fun startListening(): Boolean {
        if (isListening) return true
        val eng = engine ?: run {
            Logger.log("OpenWakeWord not initialized", TAG)
            return false
        }

        try {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false
            }

            isListening = true
            appStateManager.setWakeWordServiceListening(true)
            appStateManager.setVoiceState(VoiceState.LISTENING_WAKEWORD)

            eng.start()

            // Collect detections from the Flow
            detectionJob = engineScope.launch {
                eng.detections.collect { detection: WakeWordDetection ->
                    Logger.log("OpenWakeWord detected: ${detection.model.name} (score=${detection.score})", TAG)
                    onWakeWordDetected()
                }
            }

            Logger.log("OpenWakeWord started listening", TAG)
            return true
        } catch (e: Exception) {
            Logger.log("OpenWakeWord start error: ${e.message}", TAG)
            isListening = false
            return false
        }
    }

    override fun stopListening() {
        if (!isListening) return
        Logger.log("Stopping OpenWakeWord listening", TAG)
        isListening = false

        try {
            detectionJob?.cancel()
            detectionJob = null
            engine?.stop()
        } catch (e: Exception) {
            Logger.log("Error stopping OpenWakeWord: ${e.message}", TAG)
        }
    }

    override fun stopService() {
        stopListening()
        appStateManager.setWakeWordServiceListening(false)
        appStateManager.setVoiceState(VoiceState.IDLE)
    }

    override fun release() {
        stopService()
        try {
            engine?.release()
        } catch (e: Exception) {
            Logger.log("OpenWakeWord release error: ${e.message}", TAG)
        }
        engine = null
    }

    override fun releaseModelForMemoryPressure() {
        // OpenWakeWord ONNX models are small (~10MB), no need to release on memory pressure
    }
}
