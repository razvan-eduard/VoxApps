package com.voxapps.commander.service

import android.content.Context
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordDetection
import com.rementia.openwakeword.lib.model.WakeWordModel
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.state.VoiceState
import com.voxapps.logging.Logger
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

    private var engine: WakeWordEngine? = null
    private var detectionJob: Job? = null
    /** Read from the caller thread and written from engineScope coroutines. Its siblings in the
     *  other two wake-word engines are volatile; this one was not, so a stop could go unseen by an
     *  in-flight detection loop. */
    @Volatile private var isListening = false
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var listenStartMs = 0L

    companion object {
        private const val TAG = "OpenWakeWordEngine"

        // Priming window after each start(): OpenWakeWord's mel/embedding feature buffers aren't
        // filled yet, so the first inferences emit spurious high scores. Ignoring detections during
        // this window prevents a self-triggering loop (detect → command → re-arm → instant re-detect).
        private const val WARMUP_MS = 1000L // Reduced from 1500ms to catch early valid detections

        // Music/media playback leaks a broadband AEC residual through far more than TTS's own voice
        // does (platform AEC is speech-band tuned) — require a much higher confidence score before
        // accepting a detection while any app has the music stream active, to cut ghost triggers
        // without lowering sensitivity for genuine speech the rest of the time. User-toggleable
        // (wakeWordMusicDuckEnabled).
        private const val MUSIC_PLAYBACK_MIN_SCORE = 0.85f

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

            // Map the user's Wake Word Sensitivity setting to the detection threshold
            // (lower threshold = easier trigger = more sensitive). Applied at model
            // construction, so a sensitivity change only takes effect on the next initialize().
            val sensitivity = appStateManager.uiState.value.wakeWordSensitivity
            val threshold = WakeWordSensitivity.openWakeWordThreshold(sensitivity)
            Logger.log("Initializing OpenWakeWord with model: $modelId (resolvedPath=$resolvedPath, sensitivity=$sensitivity, threshold=$threshold)", TAG)

            val models = listOf(
                WakeWordModel(
                    name = modelName,
                    modelPath = resolvedPath,
                    threshold = threshold
                )
            )

            // Same AEC toggle already wired into the Vosk/Porcupine engines (see
            // WakeWordEngine.kt:213-214 in this same package) — without it, this engine scored raw,
            // un-cancelled mic audio (including the device's own speaker output during TTS/music
            // playback) with no echo cancellation at all, regardless of the user's setting.
            val aecEnabled = appStateManager.uiState.value.wakeWordAecEnabled
            val audioSource = if (aecEnabled) {
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                android.media.MediaRecorder.AudioSource.MIC
            }
            Logger.log("OpenWakeWord audio source: ${if (aecEnabled) "VOICE_COMMUNICATION (AEC on)" else "MIC (AEC off)"}", TAG)

            engine = WakeWordEngine(
                context = context,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 2000L,
                scope = engineScope,
                rmsGate = WakeWordSensitivity.openWakeWordRmsGate(sensitivity),
                noiseGateMargin = WakeWordSensitivity.noiseGateMargin(sensitivity),
                audioSource = audioSource
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

            listenStartMs = android.os.SystemClock.elapsedRealtime()
            eng.start()

            // Collect detections from the Flow
            detectionJob = engineScope.launch {
                eng.detections.collect { detection: WakeWordDetection ->
                    val sinceStart = android.os.SystemClock.elapsedRealtime() - listenStartMs
                    if (sinceStart < WARMUP_MS) {
                        // Feature buffers still priming — this is a startup transient, not a real hit.
                        Logger.log("OpenWakeWord warmup: ignoring detection ${detection.model.name} (score=${detection.score}, ${sinceStart}ms after start)", TAG)
                        return@collect
                    }

                    val musicDuckEnabled = appStateManager.uiState.value.wakeWordMusicDuckEnabled
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    val isMusicActive = audioManager?.isMusicActive == true
                    
                    if (musicDuckEnabled && isMusicActive && detection.score < MUSIC_PLAYBACK_MIN_SCORE) {
                        Logger.log("OpenWakeWord: IGNORED detection during music playback " +
                            "(score=${detection.score} < $MUSIC_PLAYBACK_MIN_SCORE) — model=${detection.model.name}", TAG)
                        return@collect
                    }

                    Logger.log("OpenWakeWord: ACCEPTED detection: ${detection.model.name} (score=${detection.score}, threshold=${detection.model.threshold}, musicActive=$isMusicActive)", TAG)
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

    override fun releaseForMemoryPressure() {
        // OpenWakeWord ONNX models are small (~10MB), no need to release on memory pressure
    }
}
