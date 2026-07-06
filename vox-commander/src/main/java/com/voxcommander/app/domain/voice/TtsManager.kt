package com.voxcommander.app.domain.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.engine.AndroidTtsEngine
import com.voxcommander.app.domain.engine.ITtsEngine
import com.voxcommander.app.domain.engine.PiperTtsEngine
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
    private var ttsEngineType: String = "android"
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f
    private var currentTtsLanguage: String = ""
    private var audioFocusMode: String = "duck"

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // --- REACTIVE SPEAKING STATE (for overlay UI) ---
    private val _isSpeakingFlow = MutableStateFlow(false)
    val isSpeakingFlow = _isSpeakingFlow.asStateFlow()

    private val _currentTextFlow = MutableStateFlow("")
    val currentTextFlow = _currentTextFlow.asStateFlow()

    private val _speechRateFlow = MutableStateFlow(1.0f)
    val speechRateFlow = _speechRateFlow.asStateFlow()

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

        TextNormalizer.load(context.applicationContext)

        val snapshot = settingsRepo.getSettingsSnapshot()
        ttsEnabled = snapshot.ttsEnabled
        ttsEngineType = snapshot.ttsEngineType
        speechRate = snapshot.ttsSpeechRate
        _speechRateFlow.value = speechRate
        pitch = snapshot.ttsPitch
        audioFocusMode = snapshot.ttsAudioFocusMode
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        ensureEngine(snapshot.voiceLanguage)

        startSettingsObservation()
    }

    private fun startSettingsObservation() {
        settingsObservationJob?.cancel()
        val hub = appStateManager ?: return

        settingsObservationJob = scope.launch {
            hub.uiState
                .map { Pair(it.voiceLanguage, TtsSettingsSnapshot(it.ttsEnabled, it.ttsEngineType, it.ttsSpeechRate, it.ttsPitch, it.ttsAudioFocusMode)) }
                .distinctUntilChanged()
                .collectLatest { (language, s) ->
                    val changed = ttsEnabled != s.enabled || speechRate != s.rate || pitch != s.pitch
                    val engineChanged = ttsEngineType != s.engineType
                    ttsEnabled = s.enabled
                    ttsEngineType = s.engineType
                    speechRate = s.rate
                    _speechRateFlow.value = s.rate
                    pitch = s.pitch
                    audioFocusMode = s.focusMode

                    if (changed) {
                        engine?.setSpeechRate(s.rate)
                        engine?.setPitch(s.pitch)
                    }

                    // Re-initialize engine if language or engine type changed
                    if (language != currentTtsLanguage || engineChanged) {
                        ensureEngine(language)
                    }
                }
        }
    }

    private fun ensureEngine(language: String) {
        val ctx = context ?: return
        val desiredType = TtsEngineType.fromKey(ttsEngineType) ?: TtsEngineType.ANDROID

        if (engine == null) {
            engine = createEngine(desiredType)
            val ok = engine?.initialize(ctx, language) ?: false
            if (!ok && desiredType == TtsEngineType.PIPER) {
                Logger.log("Piper TTS init failed, falling back to Android TTS", TAG)
                engine?.release()
                engine = AndroidTtsEngine()
                engine?.initialize(ctx, language)
            }
            engine?.setSpeechRate(speechRate)
            engine?.setPitch(pitch)
            currentTtsLanguage = language
            Logger.log("TTS engine created (${desiredType.key}) for language '$language'", TAG)
        } else if (language != currentTtsLanguage || !isCurrentEngineType(desiredType)) {
            Logger.log("TTS re-init: lang '$currentTtsLanguage'->'$language', engine ${if (!isCurrentEngineType(desiredType)) "changed " else ""}to ${desiredType.key}", TAG)
            engine?.stop()
            engine?.release()
            engine = createEngine(desiredType)
            val ok = engine?.initialize(ctx, language) ?: false
            if (!ok && desiredType == TtsEngineType.PIPER) {
                Logger.log("Piper TTS init failed, falling back to Android TTS", TAG)
                engine?.release()
                engine = AndroidTtsEngine()
                engine?.initialize(ctx, language)
            }
            engine?.setSpeechRate(speechRate)
            engine?.setPitch(pitch)
            currentTtsLanguage = language
        }
    }

    private fun createEngine(type: TtsEngineType): ITtsEngine = when (type) {
        TtsEngineType.ANDROID -> AndroidTtsEngine()
        TtsEngineType.PIPER -> PiperTtsEngine()
    }

    private fun isCurrentEngineType(type: TtsEngineType): Boolean {
        val eng = engine ?: return false
        return when (type) {
            TtsEngineType.ANDROID -> eng is AndroidTtsEngine
            TtsEngineType.PIPER -> eng is PiperTtsEngine
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

        val normalizedText = TextNormalizer.normalize(text, currentTtsLanguage)
        Logger.log("Speaking (normalized): ${normalizedText.take(80)}...", TAG)
        _isSpeakingFlow.value = true
        _currentTextFlow.value = normalizedText
        requestAudioFocus()
        eng.speak(normalizedText, onDone = {
            abandonAudioFocus()
            _isSpeakingFlow.value = false
            _currentTextFlow.value = ""
            resetRuntimeSpeechRate()
            onComplete?.invoke()
        })
    }

    /**
     * Stops any ongoing TTS playback immediately.
     * Called from WakeWordEngine callback for barge-in support.
     */
    fun stop() {
        engine?.stop()
        abandonAudioFocus()
        _isSpeakingFlow.value = false
        _currentTextFlow.value = ""
        resetRuntimeSpeechRate()
        Logger.log("TTS stopped", TAG)
    }

    /**
     * Dynamically changes TTS speech rate during playback (e.g. from overlay speed control).
     * Does NOT persist to settings — only affects current playback session.
     * Sentences are queued one at a time, so the new rate takes effect at the next sentence boundary.
     */
    fun setRuntimeSpeechRate(multiplier: Float) {
        val newRate = (speechRate * multiplier)
        engine?.setSpeechRate(newRate)
        _speechRateFlow.value = newRate
        Logger.log("Runtime speech rate set to $newRate (base=$speechRate, mult=$multiplier)", TAG)
    }

    /**
     * Resets speech rate back to the settings-based value.
     * Called when TTS playback ends or is stopped.
     */
    private fun resetRuntimeSpeechRate() {
        engine?.setSpeechRate(speechRate)
        _speechRateFlow.value = speechRate
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
        abandonAudioFocus()
        engine?.release()
        engine = null
        _isSpeakingFlow.value = false
        _currentTextFlow.value = ""
        currentTtsLanguage = ""
        initialized = false
        Logger.log("TtsManager released", TAG)
    }

    /**
     * Releases the active TTS engine's heavy native model (e.g. Piper's sherpa-onnx
     * model) on system memory pressure. Transparently reloaded on next speak(); no-op
     * for lightweight engines (Android TTS) or if currently speaking.
     */
    fun releaseForMemoryPressure() {
        engine?.releaseForMemoryPressure()
    }

    // --- AUDIO FOCUS ---

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (audioFocusMode == "none") return

        val focusType = if (audioFocusMode == "pause") {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }

        audioFocusRequest = com.voxcommander.app.utils.AudioFocusHelper.requestFocus(
            am, focusType, onFocusChange = { focusChange ->
                Logger.log("Audio focus changed: $focusChange", TAG)
            }
        )
        Logger.log("Audio focus requested (mode=$audioFocusMode)", TAG)
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (audioFocusMode == "none") return

        com.voxcommander.app.utils.AudioFocusHelper.abandonFocus(am, audioFocusRequest)
        audioFocusRequest = null
        Logger.log("Audio focus abandoned", TAG)
    }
}

private data class TtsSettingsSnapshot(
    val enabled: Boolean,
    val engineType: String,
    val rate: Float,
    val pitch: Float,
    val focusMode: String,
)
