package com.voxcommander.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.voxcommander.app.MainActivity
import com.voxcommander.app.R
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.data.preferences.SettingsRepositoryImpl
import com.voxcommander.app.domain.voice.VoiceManager
import com.voxcommander.app.domain.voice.WakeWordProfile
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.state.ServiceLoadingState
import com.voxcommander.app.state.VoiceState
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
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
    private lateinit var languageManager: com.voxcommander.app.domain.localization.LanguageManager
    private lateinit var voiceOverlayManager: com.voxcommander.app.ui.components.VoiceOverlayManager
    private var wakeWordEngine: IWakeWordEngine? = null
    private var notificationManager: NotificationManager? = null
    private var currentEngineDisplayName: String = "Vosk"
    private var currentModelDisplayName: String = ""

    private val CHANNEL_ID = "wake_word_service_channel"
    private val NOTIFICATION_ID = 101

    override fun onCreate() {
        super.onCreate()
        Logger.log("WakeWordService created", TAG)
        val repo = SettingsRepositoryImpl(this)
        settingsRepo = repo
        appStateManager = AppStateManager.getInstance(repo, this)
        languageManager = com.voxcommander.app.domain.localization.LanguageManager(this).apply {
            loadLanguage(settingsRepo.getSettingsSnapshot().language)
        }
        voiceOverlayManager = com.voxcommander.app.ui.components.VoiceOverlayManager(this, languageManager, appStateManager)
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
                com.voxcommander.app.domain.voice.TtsManager.isSpeakingFlow
            ) { isListening, isSpeaking -> isListening || isSpeaking }
                .collectLatest { showOverlay ->
                    Logger.log("Overlay visibility: showOverlay=$showOverlay, isListening=${VoiceManager.isListeningFlow.value}, isSpeaking=${com.voxcommander.app.domain.voice.TtsManager.isSpeakingFlow.value}", TAG)
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
                
                val container = (application as com.voxcommander.app.VoxApplication).container
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
            val snapshot = settingsRepo.getSettingsSnapshot()
            val wakeWord = snapshot.wakeWord
            val engineType = snapshot.wakeWordEngineType

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
                val initialized = wakeWordEngine?.initialize("", wakeWord) ?: false
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
                val initialized = wakeWordEngine?.initialize(modelFileName, wakeWord) ?: false
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
                val downloader = com.voxcommander.app.data.remote.ModelDownloader(this@WakeWordService)
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
        Logger.log("Wake word detected!", TAG)

        // Barge-in: if TTS is speaking, stop it and let the normal flow
        // proceed to listen for the next command
        if (com.voxcommander.app.domain.conversation.ConversationHandler.handleBargeIn()) {
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
                if (currentUiState.commandQueueEnabled || com.voxcommander.app.domain.voice.TtsManager.isSpeaking()) {
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
        val profileJson = settingsRepo.getWakeWordProfileJson()
        val hasVoiceProfile = profileJson != null
        val profileName = profileJson?.let { WakeWordProfile.fromJson(it)?.profileName }
        val finalContentText = contentText ?: when {
            voiceState == VoiceState.LISTENING_COMMAND -> languageManager.getString("vox_listening")
            voiceState == VoiceState.PROCESSING -> languageManager.getString("ww_paused_ai_thinking")
            isListening && hasVoiceProfile && profileName != null -> "${languageManager.getString("vox_listening")} $profileName"
            isListening && hasVoiceProfile -> languageManager.getString("vox_listening")
            isListening -> languageManager.getString("ww_listening_for").format(settingsRepo.getSettingsSnapshot().wakeWord)
            else -> languageManager.getString("ww_paused")
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
