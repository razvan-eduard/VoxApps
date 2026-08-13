package com.voxapps.commander.domain.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.utils.TextUtils
import java.util.Locale

/**
 * Android TextToSpeech wrapper implementing ITtsEngine.
 * Uses the system TTS service — no extra dependencies needed.
 */
class AndroidTtsEngine(private val appContext: Context) : BaseVoxEngine(), ITtsEngine {

    companion object {
        const val ENGINE_KEY = "android"
        private const val TAG = "AndroidTtsEngine"
    }

    override val engineKey: String = ENGINE_KEY

    // Written from the platform's init callback and from a cancelled load, read from whatever
    // thread asks for speech. The pair carries an invariant speak() depends on — `ready` true
    // means [tts] is a live connection — so they are only ever written together, under the
    // init lock in onLoad.
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f

    private val utteranceCallbacks = mutableMapOf<String, (() -> Unit)>()

    // Serializes access to utteranceCallbacks and the sentence-queue fields below.
    // speak()/queueNextSentence() run on the caller thread while the TTS framework
    // delivers onDone/onError on a binder thread — both touch this shared state.
    private val ttsLock = Any()

    // Sentence-by-sentence queue: only the current sentence is in the TTS queue,
    // so setSpeechRate() applies to the next sentence before it's queued.
    private var pendingSentences: List<String> = emptyList()
    private var currentSentenceIdx: Int = 0
    private var currentBaseId: String = ""
    private var currentOnDone: (() -> Unit)? = null

    /**
     * Connects to the platform TTS service and waits for it to report ready.
     *
     * The old form returned `true` immediately and let the service become ready later, which forced
     * a `pendingText` queue here for speech requested in the meantime, and meant the caller could
     * never actually know whether TTS worked. Suspending until `onInit` fires removes both: `load()`
     * returning true means the engine can speak now.
     */
    override suspend fun onLoad(spec: ModelSpec): Boolean = suspendCancellableCoroutine { cont ->
        val language = spec.language
        ready = false

        tts?.stop()
        tts?.shutdown()
        tts = null

        // Three threads meet on this connection: the caller, the binder thread the platform
        // delivers the init callback on — which can arrive before the constructor has even
        // returned — and whichever thread cancels the load. [initLock] is what makes that
        // meeting orderly, and [settle] is the single place the connection is configured and
        // the caller answered: whichever of publication and the callback arrives second runs
        // it, and a cancellation resolves the load before either can. Configuring through the
        // instance handed to [settle] rather than through the field is the point — a callback
        // for an abandoned connection must never report readiness, or it leaves the engine
        // claiming to be ready with no service behind it and every later speak() no-ops.
        val initLock = Any()
        var instance: TextToSpeech? = null
        var pendingStatus: Int? = null
        var resolved = false

        fun settle(status: Int, engine: TextToSpeech) {
            if (resolved) return
            resolved = true

            if (status != TextToSpeech.SUCCESS) {
                Logger.log("Android TTS init failed with status=$status", TAG)
                engine.shutdown()
                if (cont.isActive) cont.resume(false)
                return
            }

            val locale = localeForLanguage(language)
            val setResult = engine.setLanguage(locale)
            if (setResult == TextToSpeech.LANG_MISSING_DATA || setResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Logger.log("TTS language '$language' not supported (result=$setResult), falling back to default", TAG)
                engine.setLanguage(Locale.getDefault())
            }
            engine.setSpeechRate(speechRate)
            engine.setPitch(pitch)

            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    Logger.log("TTS utterance done: $utteranceId", TAG)
                    // Remove under the lock, then invoke the callback OUTSIDE the lock
                    // so user code never runs while holding ttsLock.
                    val cb = utteranceId?.let { id -> synchronized(ttsLock) { utteranceCallbacks.remove(id) } }
                    cb?.invoke()
                    queueNextSentence()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Logger.log("TTS error for utterance: $utteranceId", TAG)
                    val cb = utteranceId?.let { id -> synchronized(ttsLock) { utteranceCallbacks.remove(id) } }
                    cb?.invoke()
                    queueNextSentence()
                }
            })

            tts = engine
            ready = true
            Logger.log("Android TTS initialized for language '$language'", TAG)
            if (cont.isActive) cont.resume(true)
        }

        val created = TextToSpeech(appContext.applicationContext) { status ->
            synchronized(initLock) {
                val published = instance
                // The callback beat the constructor's return; publication below settles it.
                if (published == null) pendingStatus = status else settle(status, published)
            }
        }
        synchronized(initLock) {
            instance = created
            pendingStatus?.let { settle(it, created) }
        }

        cont.invokeOnCancellation {
            // The caller gave up while the service was still connecting; do not leave the
            // connection dangling, and resolve the load so a callback still in flight cannot
            // configure or claim readiness for a connection nobody owns any more.
            synchronized(initLock) {
                resolved = true
                if (tts === created) {
                    tts = null
                    ready = false
                }
                created.stop()
                created.shutdown()
            }
        }
    }

    override fun onUnload() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        synchronized(ttsLock) { utteranceCallbacks.clear() }
        Logger.log("Android TTS released", TAG)
    }

    override fun speak(text: String, utteranceId: String?, onDone: (() -> Unit)?) {
        if (!ready) {
            // Readiness is the caller's responsibility: TtsManager loads before speaking, and
            // load() now only returns once the service has reported ready.
            Logger.log("Android TTS has no service connection, cannot speak", TAG)
            onDone?.invoke()
            return
        }

        val baseId = utteranceId ?: "tts_${System.currentTimeMillis()}"

        val sentences = TextUtils.splitSentences(text)
        if (sentences.isEmpty()) {
            // Fallback: single utterance with QUEUE_FLUSH
            if (onDone != null) synchronized(ttsLock) { utteranceCallbacks[baseId] = onDone }
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(pitch)
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, baseId)
            if (result != TextToSpeech.SUCCESS) {
                Logger.log("TTS speak failed with result=$result", TAG)
                synchronized(ttsLock) { utteranceCallbacks.remove(baseId) }?.invoke()
            }
            return
        }

        synchronized(ttsLock) {
            pendingSentences = sentences
            currentSentenceIdx = 0
            currentBaseId = baseId
            currentOnDone = onDone
        }
        queueNextSentence()
    }

    private fun queueNextSentence() {
        var failedCallback: (() -> Unit)? = null
        synchronized(ttsLock) {
            if (currentSentenceIdx >= pendingSentences.size) {
                currentOnDone = null
                return
            }
            val idx = currentSentenceIdx
            val sentence = pendingSentences[idx].trim()
            val chunkId = "${currentBaseId}_$idx"
            val isLast = idx == pendingSentences.lastIndex

            // Apply current speechRate before each sentence — this is the key:
            // if setSpeechRate() was called mid-playback, the new rate takes effect now.
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(pitch)

            if (isLast) {
                currentOnDone?.let { utteranceCallbacks[chunkId] = it }
            }

            val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts?.speak(sentence, mode, null, chunkId)
            if (result != TextToSpeech.SUCCESS) {
                Logger.log("TTS speak chunk $idx failed with result=$result", TAG)
                // A chunk that never entered the queue produces no progress callback, so the
                // chain that would have spoken the rest stops here. Release the whole utterance
                // rather than only the chunk: the completion callback rides on the last sentence,
                // so failing on any earlier one would otherwise leave every caller waiting on a
                // callback the platform will never deliver — speaking state that never clears.
                utteranceCallbacks.remove(chunkId)
                failedCallback = currentOnDone
                currentOnDone = null
                pendingSentences = emptyList()
                currentSentenceIdx = 0
            } else {
                currentSentenceIdx++
            }
        }
        // Invoke outside the lock so user code never runs while holding ttsLock.
        failedCallback?.invoke()
    }

    override fun stop() {
        tts?.stop()
        synchronized(ttsLock) {
            utteranceCallbacks.clear()
            pendingSentences = emptyList()
            currentSentenceIdx = 0
            currentOnDone = null
        }
    }

    override fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate
        if (ready) tts?.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch
        if (ready) tts?.setPitch(pitch)
    }


    private fun localeForLanguage(language: String): Locale {
        return when {
            language.contains("_") -> {
                val parts = language.split("_")
                Locale(parts[0], parts.getOrElse(1) { "" })
            }
            language.length == 2 -> Locale(language)
            else -> Locale(language)
        }
    }
}
