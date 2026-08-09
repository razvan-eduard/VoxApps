package com.voxapps.commander.domain.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.engine.AndroidTtsEngine
import com.voxapps.commander.domain.engine.EngineSpecs
import com.voxapps.commander.domain.engine.EngineState
import com.voxapps.commander.domain.engine.ITtsEngine
import com.voxapps.commander.domain.engine.TtsEngines
import com.voxapps.commander.state.AppStateManager
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
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

    /** Swapped on Main (init/settings observation), but read from Dispatchers.IO in speak() and
     *  from the TTS binder thread in the onDone callback's resetRuntimeSpeechRate(). */
    @Volatile private var engine: ITtsEngine? = null
    private var context: Context? = null
    private var appStateManager: AppStateManager? = null
    private var initialized = false
    private var settingsRepo: SettingsRepository? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var settingsObservationJob: kotlinx.coroutines.Job? = null

    /** Written on Main by the settings observation, read on Dispatchers.IO at the top of speak(). */
    @Volatile private var ttsEnabled = true
    private var ttsEngineType: String = "android"
    /** Written on Main, read from the TTS binder thread via resetRuntimeSpeechRate() in onDone. */
    @Volatile private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f
    /** Written on Main by ensureEngine, read on Dispatchers.IO in speak()'s text normalization. */
    @Volatile private var currentTtsLanguage: String = ""
    /** Written on Main, read on Dispatchers.IO (requestAudioFocus) and on the binder thread
     *  (abandonAudioFocus). */
    @Volatile private var audioFocusMode: String = "duck"
    private var piperVoiceModelId: String? = null
    /** The Piper voice id the *currently live* engine instance was actually built with — distinct
     *  from [piperVoiceModelId] (the latest desired setting) so [ensureEngine] can tell a pure
     *  voice-only change apart from "nothing relevant changed" and rebuild only when needed. */
    private var currentPiperVoiceModelId: String? = null

    /** Assigned once on Main in init(), read from Dispatchers.IO and the TTS binder thread. */
    @Volatile private var audioManager: AudioManager? = null
    /**
     * Unlike its neighbours this can't be fixed with @Volatile: [abandonAudioFocus] reads it, hands
     * it to AudioFocusHelper, then nulls it — a read-modify-write — and the three writers really do
     * run on three different threads (requestAudioFocus from speak() on Dispatchers.IO,
     * abandonAudioFocus from the engine's onDone on a TTS binder thread or Piper's IO job, and both
     * again from stop()/release() on Main). @Volatile would publish the value without making the
     * pair atomic, so two overlapping utterances could abandon the same request twice or drop one.
     * Guarded by [audioFocusLock] instead; the critical sections are two AudioManager calls.
     */
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusLock = Any()

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
        this.appStateManager = appStateManager
        this.settingsRepo = settingsRepo
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
        piperVoiceModelId = snapshot.piperVoiceModelId
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        scope.launch { ensureEngine(snapshot.voiceLanguage) }

        startSettingsObservation()
    }

    private fun startSettingsObservation() {
        settingsObservationJob?.cancel()
        val hub = appStateManager ?: return

        settingsObservationJob = scope.launch {
            hub.uiState
                .map { Pair(it.voiceLanguage, TtsSettingsSnapshot(it.ttsEnabled, it.ttsEngineType, it.ttsSpeechRate, it.ttsPitch, it.ttsAudioFocusMode, it.piperVoiceModelId)) }
                .distinctUntilChanged()
                .collectLatest { (language, s) ->
                    val changed = ttsEnabled != s.enabled || speechRate != s.rate || pitch != s.pitch
                    val engineChanged = ttsEngineType != s.engineType
                    val voiceChanged = piperVoiceModelId != s.piperVoiceModelId
                    ttsEnabled = s.enabled
                    ttsEngineType = s.engineType
                    speechRate = s.rate
                    _speechRateFlow.value = s.rate
                    pitch = s.pitch
                    audioFocusMode = s.focusMode
                    piperVoiceModelId = s.piperVoiceModelId

                    if (changed) {
                        engine?.setSpeechRate(s.rate)
                        engine?.setPitch(s.pitch)
                    }

                    // Re-initialize engine if language, engine type, or the selected Piper voice changed
                    if (language != currentTtsLanguage || engineChanged || voiceChanged) {
                        ensureEngine(language)
                    }
                }
        }
    }

    /**
     * Brings the engine in line with the current selection, whatever changed.
     *
     * One path, not two. This used to be an `engine == null` branch and an "did anything change"
     * branch with the same body written twice — and the change detection compared engine *types*,
     * which is how selecting Piper stayed a no-op: the key never matched, so the desired type came
     * out as Android, which the engine already was. Now the engine decides: loading the same spec is
     * a no-op inside [com.voxapps.commander.domain.engine.BaseVoxEngine], so this can always ask.
     */
    private suspend fun ensureEngine(language: String) {
        val ctx = context ?: return
        val desiredKey = ttsEngineType

        if (engine?.engineKey != desiredKey) {
            engine?.stop()
            engine?.release()
            engine = TtsEngines.create(desiredKey, ctx)
            Logger.log("TTS engine created ($desiredKey)", TAG)
        }

        val loaded = loadOrNull(ctx, desiredKey, language)
        if (!loaded && desiredKey != AndroidTtsEngine.ENGINE_KEY) {
            Logger.log("'$desiredKey' could not load, falling back to Android TTS", TAG)
            engine?.release()
            engine = TtsEngines.create(AndroidTtsEngine.ENGINE_KEY, ctx)
            loadOrNull(ctx, AndroidTtsEngine.ENGINE_KEY, language)
        }

        engine?.setSpeechRate(speechRate)
        engine?.setPitch(pitch)
        currentTtsLanguage = language
        currentPiperVoiceModelId = piperVoiceModelId
    }

    private suspend fun loadOrNull(ctx: Context, engineKey: String, language: String): Boolean {
        val eng = engine ?: return false
        val repo = settingsRepo ?: return false
        val spec = EngineSpecs.build(ctx, repo, engineKey, selectedModelFor(engineKey), language) ?: return false
        return eng.load(spec)
    }

    /**
     * The model the user picked for [engineKey], falling back to any downloaded one.
     *
     * The fallback preserves the old behaviour for someone who downloaded a voice but never
     * explicitly selected it — the engine used to find it by scanning for `vits-piper-*` directory
     * names and guessing by quality suffix. This asks the registry and the downloaded set instead,
     * so it holds for any engine rather than for one naming convention.
     */
    private fun selectedModelFor(engineKey: String): String? {
        // Stored under an engine-specific key for historical reasons; it is the TTS model id.
        piperVoiceModelId?.takeIf { it.isNotBlank() }?.let { return it }

        val downloaded = settingsRepo?.getSettingsSnapshot()?.downloadedModelIds ?: return null
        return RemoteModelRegistry.getModels(engineKey).firstOrNull { it.id in downloaded }?.id
    }

    /**
     * Brings the engine in line with the current selection, whatever changed.
     *
     * One path, not two. This used to be an `engine == null` branch and an "did anything change"
     * branch with the same body written twice — and the change detection compared engine *types*,
     * which is how selecting Piper stayed a no-op: the key never matched, so the desired type came
     * out as Android, which the engine already was. Now the engine decides: loading the same spec is
     * a no-op inside [com.voxapps.commander.domain.engine.BaseVoxEngine], so this can always ask.
     */

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

        val onDone: () -> Unit = {
            abandonAudioFocus()
            _isSpeakingFlow.value = false
            _currentTextFlow.value = ""
            resetRuntimeSpeechRate()
            onComplete?.invoke()
        }

        if (eng.state.value is EngineState.Ready) {
            eng.speak(normalizedText, onDone = onDone)
            return
        }

        // The model was released under memory pressure, or has not been loaded yet. Reloading is
        // this manager's job now: the engine used to do it inside speak(), which meant a blocking
        // model load on whatever thread happened to ask for speech.
        scope.launch {
            ensureEngine(currentTtsLanguage)
            val ready = engine
            if (ready == null || ready.state.value !is EngineState.Ready) {
                Logger.log("TTS could not be made ready, dropping utterance", TAG)
                onDone()
            } else {
                ready.speak(normalizedText, onDone = onDone)
            }
        }
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

    private fun requestAudioFocus() = synchronized(audioFocusLock) {
        val am = audioManager ?: return
        if (audioFocusMode == "none") return

        val focusType = if (audioFocusMode == "pause") {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }

        audioFocusRequest = com.voxapps.commander.utils.AudioFocusHelper.requestFocus(
            am, focusType, onFocusChange = { focusChange ->
                Logger.log("Audio focus changed: $focusChange", TAG)
            }
        )
        Logger.log("Audio focus requested (mode=$audioFocusMode)", TAG)
    }

    private fun abandonAudioFocus() = synchronized(audioFocusLock) {
        val am = audioManager ?: return
        if (audioFocusMode == "none") return

        com.voxapps.commander.utils.AudioFocusHelper.abandonFocus(am, audioFocusRequest)
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
    val piperVoiceModelId: String?,
)
