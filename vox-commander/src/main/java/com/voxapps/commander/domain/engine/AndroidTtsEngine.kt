package com.voxapps.commander.domain.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.utils.TextUtils
import java.util.Locale

/**
 * Android TextToSpeech wrapper implementing ITtsEngine.
 * Uses the system TTS service — no extra dependencies needed.
 */
class AndroidTtsEngine : ITtsEngine {

    companion object {
        private const val TAG = "AndroidTtsEngine"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var currentLanguage: String = "en"
    private var pendingText: String? = null
    private var pendingUtteranceId: String? = null
    private var pendingOnDone: (() -> Unit)? = null
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

    override fun initialize(context: Context, language: String): Boolean {
        currentLanguage = language
        ready = false

        tts?.stop()
        tts?.shutdown()
        tts = null

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = localeForLanguage(language)
                val setResult = tts?.setLanguage(locale)
                if (setResult == TextToSpeech.LANG_MISSING_DATA || setResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Logger.log("TTS language '$language' not supported (result=$setResult), falling back to default", TAG)
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(pitch)

                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
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

                ready = true
                Logger.log("Android TTS initialized for language '$language'", TAG)

                // If text was queued before init completed, speak it now
                pendingText?.let { text ->
                    pendingText = null
                    speak(text, pendingUtteranceId, pendingOnDone)
                    pendingUtteranceId = null
                    pendingOnDone = null
                }
            } else {
                Logger.log("Android TTS init failed with status=$status", TAG)
            }
        }

        return true
    }

    override fun speak(text: String, utteranceId: String?, onDone: (() -> Unit)?) {
        if (!ready) {
            Logger.log("TTS not ready yet, queuing text", TAG)
            pendingText = text
            pendingUtteranceId = utteranceId
            pendingOnDone = onDone
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
                failedCallback = utteranceCallbacks.remove(chunkId)
            }
            currentSentenceIdx++
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
        pendingText = null
        pendingOnDone = null
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

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        synchronized(ttsLock) { utteranceCallbacks.clear() }
        pendingText = null
        pendingOnDone = null
        Logger.log("Android TTS released", TAG)
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
