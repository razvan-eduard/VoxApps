package com.voxcommander.app.domain.voice

import android.content.Context
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.engine.AndroidTtsEngine
import com.voxcommander.app.domain.engine.ITtsEngine
import com.voxcommander.app.domain.engine.TtsEngineType
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Singleton manager for TTS engine lifecycle.
 * Mirrors the VoiceManager pattern: init → reactive observation → speak/stop → release.
 *
 * Supports barge-in: [stop] can be called from WakeWordEngine callback
 * to immediately interrupt ongoing TTS playback.
 */
object TtsManager {

    private const val TAG = Strings.Tags.TTS_MANAGER

    private var engine: ITtsEngine? = null
    private var context: Context? = null
    private var settingsRepo: SettingsRepository? = null
    private var appStateManager: AppStateManager? = null
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var settingsObservationJob: kotlinx.coroutines.Job? = null

    private var ttsEnabled = true
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f
    private var currentTtsLanguage: String = ""

    // --- REACTIVE SPEAKING STATE (for overlay UI) ---
    private val _isSpeakingFlow = MutableStateFlow(false)
    val isSpeakingFlow = _isSpeakingFlow.asStateFlow()

    private val _currentTextFlow = MutableStateFlow("")
    val currentTextFlow = _currentTextFlow.asStateFlow()

    /**
     * Initializes the TTS engine and starts reactive observation of settings.
     */
    fun init(
        context: Context,
        settingsRepo: SettingsRepository,
        appStateManager: AppStateManager
    ) {
        if (initialized) {
            Logger.log("TtsManager already initialized", TAG)
            return
        }

        this.context = context.applicationContext
        this.settingsRepo = settingsRepo
        this.appStateManager = appStateManager
        this.initialized = true

        Logger.log("TtsManager initialized", TAG)

        val snapshot = settingsRepo.getSettingsSnapshot()
        ttsEnabled = snapshot.ttsEnabled
        speechRate = snapshot.ttsSpeechRate
        pitch = snapshot.ttsPitch

        ensureEngine(snapshot.voiceLanguage)

        startSettingsObservation()
    }

    private fun startSettingsObservation() {
        settingsObservationJob?.cancel()
        val hub = appStateManager ?: return

        settingsObservationJob = scope.launch {
            hub.uiState
                .map { Triple(it.voiceLanguage, it.ttsEnabled, it.ttsSpeechRate to it.ttsPitch) }
                .distinctUntilChanged()
                .collectLatest { (language, enabled, rateAndPitch) ->
                    val (rate, p) = rateAndPitch
                    val changed = ttsEnabled != enabled || speechRate != rate || pitch != p
                    ttsEnabled = enabled
                    speechRate = rate
                    pitch = p

                    if (changed) {
                        engine?.setSpeechRate(rate)
                        engine?.setPitch(p)
                    }

                    // Re-initialize engine if language changed
                    ensureEngine(language)
                }
        }
    }

    private fun ensureEngine(language: String) {
        val ctx = context ?: return
        if (engine == null) {
            engine = AndroidTtsEngine()
            engine?.initialize(ctx, language)
            engine?.setSpeechRate(speechRate)
            engine?.setPitch(pitch)
            currentTtsLanguage = language
            Logger.log("TTS engine created for language '$language'", TAG)
        } else if (language != currentTtsLanguage) {
            Logger.log("TTS language changed '$currentTtsLanguage' -> '$language', re-initializing", TAG)
            engine?.stop()
            engine?.release()
            engine = AndroidTtsEngine()
            engine?.initialize(ctx, language)
            engine?.setSpeechRate(speechRate)
            engine?.setPitch(pitch)
            currentTtsLanguage = language
        }
    }

    /**
     * Speaks the given text. If TTS is disabled, this is a no-op.
     * @param text The text to speak.
     * @param onComplete Optional callback invoked when playback finishes or is interrupted.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!ttsEnabled) {
            Logger.log("TTS disabled, skipping speak", TAG)
            onComplete?.invoke()
            return
        }

        val eng = engine
        if (eng == null) {
            Logger.log("TTS engine not available, skipping speak", TAG)
            onComplete?.invoke()
            return
        }

        Logger.log("Speaking: ${text.take(80)}...", TAG)
        _currentTextFlow.value = text
        _isSpeakingFlow.value = true
        eng.speak(text, onDone = {
            _isSpeakingFlow.value = false
            _currentTextFlow.value = ""
            onComplete?.invoke()
        })
    }

    /**
     * Stops any ongoing TTS playback immediately.
     * Called from WakeWordEngine callback for barge-in support.
     */
    fun stop() {
        engine?.stop()
        _isSpeakingFlow.value = false
        _currentTextFlow.value = ""
        Logger.log("TTS stopped", TAG)
    }

    /**
     * Whether TTS is currently speaking.
     */
    fun isSpeaking(): Boolean = _isSpeakingFlow.value

    /**
     * Releases the TTS engine and cleans up resources.
     */
    fun release() {
        settingsObservationJob?.cancel()
        settingsObservationJob = null
        engine?.stop()
        engine?.release()
        engine = null
        _isSpeakingFlow.value = false
        _currentTextFlow.value = ""
        currentTtsLanguage = ""
        initialized = false
        Logger.log("TtsManager released", TAG)
    }
}
