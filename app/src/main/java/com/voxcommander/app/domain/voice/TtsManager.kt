package com.voxcommander.app.domain.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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

        val snapshot = settingsRepo.getSettingsSnapshot()
        ttsEnabled = snapshot.ttsEnabled
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
                .map { Triple(it.voiceLanguage, it.ttsEnabled, Triple(it.ttsSpeechRate, it.ttsPitch, it.ttsAudioFocusMode)) }
                .distinctUntilChanged()
                .collectLatest { (language, enabled, ratePitchFocus) ->
                    val (rate, p, focusMode) = ratePitchFocus
                    val changed = ttsEnabled != enabled || speechRate != rate || pitch != p
                    ttsEnabled = enabled
                    speechRate = rate
                    _speechRateFlow.value = rate
                    pitch = p
                    audioFocusMode = focusMode

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
        _isSpeakingFlow.value = true
        _currentTextFlow.value = text
        requestAudioFocus()
        eng.speak(text, onDone = {
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

    // --- AUDIO FOCUS ---

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (audioFocusMode == "none") return

        val focusType = if (audioFocusMode == "pause") {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(focusType)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    Logger.log("Audio focus changed: $focusChange", TAG)
                }
                .setAcceptsDelayedFocusGain(true)
                .build()
            am.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, focusType)
        }
        Logger.log("Audio focus requested (mode=$audioFocusMode)", TAG)
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (audioFocusMode == "none") return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        Logger.log("Audio focus abandoned", TAG)
    }
}
