package com.voxapps.commander.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.voxapps.commander.MainActivity
import com.voxapps.commander.R
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.preferences.SettingsRepositoryImpl
import com.voxapps.commander.domain.voice.VoiceManager
import com.voxapps.commander.domain.voice.WakeWordProfile
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.state.ServiceLoadingState
import com.voxapps.commander.state.VoiceState
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class WakeWordService : Service() {

    private val TAG = "WakeWordService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var appStateManager: AppStateManager
    private lateinit var languageManager: com.voxapps.commander.domain.localization.LanguageManager
    private lateinit var voiceOverlayManager: com.voxapps.commander.ui.components.VoiceOverlayManager
    private var wakeWordEngine: IWakeWordEngine? = null
    private var notificationManager: NotificationManager? = null
    private var currentEngineDisplayName: String = "Vosk"
    private var currentModelDisplayName: String = ""

    private val CHANNEL_ID = "wake_word_service_channel"
    private val NOTIFICATION_ID = 101

    // App-level debounce: a wake word + its command flow always takes longer than this,
    // so no legitimate trigger is lost, but rapid re-fires (e.g. residual audio right after
    // an engine restart, or an over-eager OpenWakeWord threshold) are suppressed.
    private val WAKE_DEBOUNCE_MS = 2500L
    @Volatile private var lastWakeTriggerMs = 0L

    override fun onCreate() {
        super.onCreate()
        Logger.log("WakeWordService created", TAG)
        val repo = SettingsRepositoryImpl(this)
        settingsRepo = repo
        appStateManager = AppStateManager.getInstance(repo, this)
        languageManager = com.voxapps.commander.domain.localization.LanguageManager(this).apply {
            loadLanguage(settingsRepo.getSettingsSnapshot().language)
        }
        voiceOverlayManager = com.voxapps.commander.ui.components.VoiceOverlayManager(this, languageManager, appStateManager)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        serviceScope.launch {
            appStateManager.uiState.collectLatest { uiState ->
                handleVoiceStateChange(uiState.voiceState)
            }
        }

        // --- PROFILE CHANGE OBSERVER: update notification when profile is created/deleted ---
        serviceScope.launch {
            appStateManager.uiState.map { it.wakeWordProfileJson }.distinctUntilChanged().collect {
                if (wakeWordEngine != null) updateNotification()
            }
        }

        // --- VISIBILITY CONTROLLER ---
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                VoiceManager.isListeningFlow,
                com.voxapps.commander.domain.voice.TtsManager.isSpeakingFlow
            ) { isListening, isSpeaking -> isListening || isSpeaking }
                .collectLatest { showOverlay ->
                    Logger.log("Overlay visibility: showOverlay=$showOverlay, isListening=${VoiceManager.isListeningFlow.value}, isSpeaking=${com.voxapps.commander.domain.voice.TtsManager.isSpeakingFlow.value}", TAG)
                    val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.provider.Settings.canDrawOverlays(this@WakeWordService)
                    } else true

                    if (showOverlay && canDraw) {
                        voiceOverlayManager.show()
                    } else {
                        voiceOverlayManager.hide()
                    }
                }
        }

        // --- BACKGROUND TRIGGER (event-based, no boolean state) ---
        serviceScope.launch {
            appStateManager.wakeWordEvents.collect {
                Logger.log("WakeWordService: Background trigger activated!", TAG)
                val uiState = appStateManager.uiState.value
                
                val container = (application as com.voxapps.commander.VoxApplication).container
                if (uiState.voiceState == VoiceState.PROCESSING && uiState.commandQueueEnabled) {
                    // AI is busy and queue is enabled — queue the new command
                    Logger.log("AI busy (PROCESSING) — enqueuing voice command", TAG)
                    container.mainViewModel.enqueueVoiceCommand(
                        uiState.voiceLanguage,
                        uiState.voiceProcessor
                    )
                } else if (uiState.voiceState == VoiceState.PROCESSING) {
                    // Queue disabled — ignore second trigger while busy
                    Logger.log("AI busy but queue disabled — ignoring wake word", TAG)
                } else {
                    container.mainViewModel.processVoiceCommand(
                        uiState.voiceLanguage,
                        uiState.voiceProcessor
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Logger.log("WakeWordService onStartCommand: $action", TAG)

        when (action) {
            ACTION_START -> startWakeWordDetection()
            ACTION_PAUSE -> pauseWakeWordDetection()
            ACTION_RESUME -> resumeWakeWordDetection()
            ACTION_EXTERNAL_TRIGGER -> {
                // Started by external automation app (MacroDroid, Tasker, etc.)
                // Start foreground service first, then trigger listening
                startForeground(NOTIFICATION_ID, createNotification())
                if (wakeWordEngine == null) {
                    Logger.log("External trigger: service not initialized — starting wake word detection first", TAG)
                    startWakeWordDetection()
                }
                onWakeWordDetected()
            }
            ACTION_STOP, ACTION_EXIT -> {
                stopWakeWordDetection()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                notificationManager?.cancel(NOTIFICATION_ID)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Logger.log("onTrimMemory: level=$level", TAG)
        when (level) {
            TRIM_MEMORY_BACKGROUND, TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_UI_HIDDEN -> {
                // Release the Vosk model (can be 1.8GB+) to prevent LOW_MEMORY kill.
                // The model will be re-loaded on the next startListening() call.
                Logger.log("Memory pressure detected ($level) — releasing wake word model", TAG)
                wakeWordEngine?.releaseForMemoryPressure()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log("WakeWordService destroyed", TAG)
        serviceScope.cancel()
        stopWakeWordDetection()
        appStateManager.setVoiceState(VoiceState.IDLE)
    }

    private fun startWakeWordDetection() {
        Logger.log("Starting wake word detection", TAG)

        if (appStateManager.uiState.value.voiceState != VoiceState.IDLE) {
            Logger.log("Service was in state ${appStateManager.uiState.value.voiceState}. Forcing IDLE...", TAG)
            appStateManager.setVoiceState(VoiceState.IDLE)
        }

        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            // Ensure the model registry is loaded in this process (loads from filesDir, no network)
            // so hasCapability()/model labels resolve in the notification even on a cold start.
            com.voxapps.commander.data.remote.RemoteModelRegistry.fetchJson(settingsRepo, force = false)

            val snapshot = settingsRepo.getSettingsSnapshot()
            val wakeWord = snapshot.wakeWord
            val engineType = snapshot.wakeWordEngineType

            // snapshot.wakeWord is Commander's single global wake-PHRASE setting -- only meaningful
            // for Vosk, which detects an arbitrary configurable phrase. OpenWakeWord/Porcupine
            // detect whatever their selected pre-trained model was built for (Alexa, Hey Jarvis,
            // etc.), so passing the Vosk phrase into their initialize() calls mislabels every
            // detection under the wrong name (confirmed on-device: OpenWakeWord correctly detected
            // Alexa with a real score, but logged/displayed as "hi vosk", which read as if the wrong
            // engine were running at all). Resolve the real model label from the registry instead.
            fun engineDisplayWakeWord(registryKey: String): String =
                snapshot.wakeWordModelPath
                    ?.let { modelId -> com.voxapps.commander.data.remote.RemoteModelRegistry.getModels(registryKey).find { it.id == modelId }?.label }
                    ?: wakeWord

            val engineDisplayName = when (engineType) {
                "wake_porcupine", "porcupine" -> "Porcupine"
                "wake_openwakeword", "openwakeword" -> "OpenWakeWord"
                else -> "Vosk"
            }
            val modelDisplayName = snapshot.wakeWordModelPath ?: snapshot.modelFilterLang.uppercase()

            currentEngineDisplayName = engineDisplayName
            currentModelDisplayName = modelDisplayName

            appStateManager.setServiceLoading(ServiceLoadingState(
                isActive = true,
                serviceName = "Wake Word",
                engineName = engineDisplayName,
                modelName = modelDisplayName
            ))

            wakeWordEngine?.release()
            wakeWordEngine = null

            if (engineType == "wake_porcupine" || engineType == "porcupine") {
                Logger.log("Using Porcupine wake word engine", TAG)
                wakeWordEngine = PorcupineWakeWordEngine(this@WakeWordService, settingsRepo, appStateManager) {
                    onWakeWordDetected()
                }
                val initialized = wakeWordEngine?.initialize("", engineDisplayWakeWord("wake_porcupine")) ?: false
                if (initialized) {
                    wakeWordEngine?.startListening()
                    delay(100)
                    updateNotification()
                    appStateManager.clearServiceLoading()
                } else {
                    Logger.log("Failed to initialize Porcupine engine", TAG)
                    appStateManager.clearServiceLoading()
                    stopSelf()
                }
            } else if (engineType == "wake_openwakeword" || engineType == "openwakeword") {
                Logger.log("Using OpenWakeWord engine", TAG)
                val modelFileName = snapshot.wakeWordModelPath ?: "alexa_v0.1.onnx"
                wakeWordEngine = OpenWakeWordEngine(this@WakeWordService, appStateManager) {
                    onWakeWordDetected()
                }
                val initialized = wakeWordEngine?.initialize(modelFileName, engineDisplayWakeWord("wake_openwakeword")) ?: false
                if (initialized) {
                    wakeWordEngine?.startListening()
                    delay(100)
                    updateNotification()
                    appStateManager.clearServiceLoading()
                } else {
                    Logger.log("Failed to initialize OpenWakeWord engine", TAG)
                    appStateManager.clearServiceLoading()
                    stopSelf()
                }
            } else {
                Logger.log("Using Vosk wake word engine", TAG)
                val wakeWordModelName = snapshot.wakeWordModelPath
                val modelFilterLang = snapshot.modelFilterLang

                val rootDir = getExternalFilesDir(null)
                val modelPath = if (!wakeWordModelName.isNullOrBlank()) {
                    val directFile = File(rootDir, wakeWordModelName)
                    if (directFile.exists()) {
                        directFile.absolutePath
                    } else {
                        rootDir?.listFiles()?.find {
                            it.isDirectory && it.name.contains(wakeWordModelName, ignoreCase = true)
                        }?.absolutePath
                    }
                } else {
                    rootDir?.listFiles()?.find {
                        it.isDirectory && it.name.startsWith("vosk-model-") && it.name.contains(modelFilterLang, ignoreCase = true)
                    }?.absolutePath
                }

                if (modelPath == null) {
                    Logger.log("No Vosk model available", TAG)
                    appStateManager.clearServiceLoading()
                    stopSelf()
                    return@launch
                }

                // Validate model integrity before initializing
                val modelDir = File(modelPath)
                val modelId = modelDir.name
                val downloader = com.voxapps.commander.data.remote.ModelDownloader(this@WakeWordService)
                if (!downloader.validateModel(modelId, engineType)) {
                    Logger.log("Vosk model $modelId is corrupt/incomplete — cleaning up and marking for re-download", TAG)
                    settingsRepo.setModelDownloaded(modelId, false)
                    appStateManager.clearServiceLoading()
                    stopSelf()
                    return@launch
                }

                wakeWordEngine = WakeWordEngine(this@WakeWordService, settingsRepo, appStateManager) {
                    onWakeWordDetected()
                }

                val initialized = wakeWordEngine?.initialize(modelPath, wakeWord) ?: false
                if (initialized) {
                    wakeWordEngine?.startListening()
                    delay(100)
                    updateNotification()
                    appStateManager.clearServiceLoading()
                } else {
                    Logger.log("Failed to initialize Vosk engine", TAG)
                    appStateManager.clearServiceLoading()
                    stopSelf()
                }
            }
        }
    }

    private fun stopWakeWordDetection() {
        Logger.log("Stopping wake word detection and releasing", TAG)
        val engineDisplayName = when (settingsRepo.getSettingsSnapshot().wakeWordEngineType) {
            "wake_porcupine", "porcupine" -> "Porcupine"
            "wake_openwakeword", "openwakeword" -> "OpenWakeWord"
            else -> "Vosk"
        }
        appStateManager.setServiceLoading(ServiceLoadingState(
            isActive = true,
            serviceName = "Wake Word",
            engineName = engineDisplayName,
            isStopping = true
        ))
        wakeWordEngine?.release()
        wakeWordEngine = null
        appStateManager.clearServiceLoading()
    }

    private fun pauseWakeWordDetection() {
        Logger.log("Pausing wake word detection", TAG)
        wakeWordEngine?.stopListening()
        appStateManager.setWakeWordServiceListening(false)
        updateNotification()
    }

    private fun resumeWakeWordDetection() {
        Logger.log("Resuming wake word detection", TAG)
        wakeWordEngine?.startListening()
        appStateManager.setWakeWordServiceListening(true)
        updateNotification()
    }

    private fun onWakeWordDetected() {
        // App-level debounce — drop triggers that arrive too soon after the last accepted one.
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastWakeTriggerMs
        if (lastWakeTriggerMs != 0L && sinceLast < WAKE_DEBOUNCE_MS) {
            Logger.log("Wake word debounced (${sinceLast}ms < ${WAKE_DEBOUNCE_MS}ms since last trigger)", TAG)
            return
        }
        lastWakeTriggerMs = now

        Logger.log("Wake word detected!", TAG)

        // Barge-in: if TTS is speaking, stop it and let the normal flow
        // proceed to listen for the next command
        if (com.voxapps.commander.domain.conversation.ConversationHandler.handleBargeIn()) {
            Logger.log("Barge-in handled — TTS stopped, transitioning to listen", TAG)
        }

        // Stop AudioRecord and release audio focus IMMEDIATELY before anything else
        // so other apps (Spotify etc.) can reclaim audio and VoiceManager can grab the mic
        wakeWordEngine?.stopListening()
        appStateManager.onWakeWordDetected()
        playHapticFeedback()
    }

    private suspend fun handleVoiceStateChange(state: VoiceState) {
        val currentUiState = appStateManager.uiState.value
        val isServiceActive = wakeWordEngine != null

        when (state) {
            VoiceState.IDLE -> {
                if (isServiceActive && currentUiState.isWakeWordServiceListening) {
                    delay(1500) // Cooldown: let Vosk buffer flush before restarting
                    if (appStateManager.uiState.value.voiceState == VoiceState.IDLE &&
                        appStateManager.uiState.value.isWakeWordServiceListening) {
                        val started = wakeWordEngine?.startListening() ?: false
                        if (started) {
                            updateNotification()
                        } else {
                            Logger.log("startListening failed on IDLE retry — retrying in 2s", TAG)
                            delay(2000)
                            if (appStateManager.uiState.value.voiceState == VoiceState.IDLE &&
                                appStateManager.uiState.value.isWakeWordServiceListening) {
                                val retried = wakeWordEngine?.startListening() ?: false
                                if (retried) {
                                    updateNotification()
                                } else {
                                    Logger.log("startListening failed after retry — giving up", TAG)
                                }
                            }
                        }
                    }
                }
            }
            VoiceState.LISTENING_WAKEWORD -> {
                updateNotification()
            }
            VoiceState.LISTENING_COMMAND -> {
                wakeWordEngine?.stopListening()
                updateNotification(languageManager.getString("vox_listening"))
            }
            VoiceState.PROCESSING -> {
                if (currentUiState.commandQueueEnabled || com.voxapps.commander.domain.voice.TtsManager.isSpeaking()) {
                    // Keep WW running during PROCESSING so user can queue next command
                    // or barge-in during TTS playback
                    Logger.log("WW staying active during PROCESSING (queue/barge-in mode)", TAG)
                } else {
                    wakeWordEngine?.stopListening()
                    updateNotification(languageManager.getString("ww_paused_ai_thinking"))
                }
            }
            VoiceState.BENCHMARKING -> {
                wakeWordEngine?.stopListening()
                updateNotification(languageManager.getString("ww_paused_diagnostics"))
            }
            else -> {
                wakeWordEngine?.stopListening()
            }
        }
    }

    private fun playHapticFeedback() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake Word Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Monitors microphone for wake word" }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String? = null): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val uiState = appStateManager.uiState.value
        val isListening = uiState.isWakeWordServiceListening

        val voiceState = uiState.voiceState
        val snapshot = settingsRepo.getSettingsSnapshot()
        val engineType = snapshot.wakeWordEngineType
        // Capability-driven: engines with `builtin_models` (OpenWakeWord, Porcupine) use the
        // selected model/keyword AS the wake word — there is no free-text word and any (Vosk-only)
        // voice profile is irrelevant. Engines without it (Vosk) use a calibrated voice profile or
        // the manually-typed wake word.
        val modelDeterminesWakeWord =
            com.voxapps.commander.data.remote.RemoteModelRegistry.hasCapability(engineType, "builtin_models")
        val profileJson = settingsRepo.getWakeWordProfileJson()
        val hasVoiceProfile = profileJson != null && !modelDeterminesWakeWord
        val profileName = profileJson?.let { WakeWordProfile.fromJson(it)?.profileName }

        // Clean label of the selected wake model (Porcupine keyword / OWW model), falling back to
        // the raw id then the cached display name if the registry isn't loaded.
        val modelLabel: String? = if (modelDeterminesWakeWord) {
            val modelId = snapshot.wakeWordModelPath
            val raw = if (!modelId.isNullOrBlank()) {
                com.voxapps.commander.data.remote.RemoteModelRegistry.getModels(engineType)
                    .find { it.id == modelId }?.label ?: modelId
            } else currentModelDisplayName.ifBlank { null }
            // Keep the leading name (letters, underscores, spaces — so multi-word keywords like
            // "Hey Jarvis" survive) but drop everything from the first version/format character
            // (digit, '.', '-', '(') onward: "Modelul_meu_wake-0.1.1" -> "Modelul_meu_wake".
            raw?.let { s -> Regex("^[^-0-9.(]+").find(s)?.value?.trim()?.takeIf { it.isNotBlank() } ?: s }
        } else null

        val finalContentText = contentText ?: when {
            voiceState == VoiceState.LISTENING_COMMAND -> languageManager.getString("vox_listening")
            voiceState == VoiceState.PROCESSING -> languageManager.getString("ww_paused_ai_thinking")
            !isListening -> languageManager.getString("ww_paused")
            // Porcupine / OpenWakeWord: the selected model determines the wake word.
            modelDeterminesWakeWord && !modelLabel.isNullOrBlank() ->
                languageManager.getString("ww_listening_for").format(modelLabel)
            // Vosk with a calibrated voice profile: show the profile name.
            hasVoiceProfile && profileName != null -> "${languageManager.getString("vox_listening")} $profileName"
            hasVoiceProfile -> languageManager.getString("vox_listening")
            // Vosk with a manual wake word: show the word.
            else -> languageManager.getString("ww_listening_for").format(snapshot.wakeWord)
        }

        val engineSubtext = if (isListening && !hasVoiceProfile) "$currentEngineDisplayName · $currentModelDisplayName" else null

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vox Commander")
            .setContentText(finalContentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setStyle(MediaNotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1))

        if (engineSubtext != null) builder.setSubText(engineSubtext)

        // 1. Action: Pause/Resume Toggle
        if (isListening) {
            val pauseIntent = Intent(this, WakeWordService::class.java).apply { action = ACTION_PAUSE }
            builder.addAction(android.R.drawable.ic_media_pause, languageManager.getString("notification_pause"), PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            val resumeIntent = Intent(this, WakeWordService::class.java).apply { action = ACTION_RESUME }
            builder.addAction(android.R.drawable.ic_media_play, languageManager.getString("notification_resume"), PendingIntent.getService(this, 2, resumeIntent, PendingIntent.FLAG_IMMUTABLE))
        }

        // 2. Action: Stop (Exit)
        val stopIntent = Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, languageManager.getString("notification_stop"), PendingIntent.getService(this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE))

        return builder.build()
    }

    private fun updateNotification(text: String? = null) {
        notificationManager?.notify(NOTIFICATION_ID, createNotification(text))
    }

    companion object {
        const val ACTION_START = Strings.Actions.START_WAKE_WORD
        const val ACTION_STOP = Strings.Actions.STOP_WAKE_WORD
        const val ACTION_PAUSE = Strings.Actions.PAUSE_WAKE_WORD
        const val ACTION_RESUME = Strings.Actions.RESUME_WAKE_WORD
        const val ACTION_EXIT = Strings.Actions.EXIT_SERVICE
        const val ACTION_EXTERNAL_TRIGGER = "com.voxapps.commander.EXTERNAL_TRIGGER"

        fun startService(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stopService(context: Context) {
            context.startService(Intent(context, WakeWordService::class.java).apply { action = ACTION_STOP })
        }
    }
}
