package com.voxcommander.app.domain.engine

/**
 * Pluggable Text-to-Speech engine interface.
 * Mirrors the SttEngine pattern: init → use → release.
 */
interface ITtsEngine {

    /**
     * Initializes the engine with the given context and language.
     * @return true if initialization succeeded.
     */
    fun initialize(context: android.content.Context, language: String): Boolean

    /**
     * Speaks the given text. If [utteranceId] is non-null, [onDone] is invoked
     * when playback finishes (or is interrupted).
     */
    fun speak(text: String, utteranceId: String? = null, onDone: (() -> Unit)? = null)

    /**
     * Stops any ongoing playback immediately.
     */
    fun stop()

    /**
     * Whether the engine is currently speaking.
     */
    fun isSpeaking(): Boolean

    /**
     * Sets the speech rate. 1.0 = normal, 0.5 = slow, 2.0 = fast.
     */
    fun setSpeechRate(rate: Float)

    /**
     * Sets the pitch. 1.0 = normal.
     */
    fun setPitch(pitch: Float)

    /**
     * Releases all resources. After calling this, the engine must be
     * re-initialized before use.
     */
    fun release()
}

/**
 * Supported TTS engine types.
 */
enum class TtsEngineType(val key: String) {
    ANDROID("android"),
    PIPER("piper");

    companion object {
        fun fromKey(key: String?): TtsEngineType? = entries.find { it.key == key }
    }
}
