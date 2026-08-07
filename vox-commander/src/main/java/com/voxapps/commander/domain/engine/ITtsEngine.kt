package com.voxapps.commander.domain.engine

/**
 * Pluggable Text-to-Speech engine interface.
 * Mirrors the SttEngine pattern: init → use → release.
 */
interface ITtsEngine : VoxEngine {

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

}
