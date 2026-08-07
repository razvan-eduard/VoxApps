package com.voxapps.commander.domain.engine

/**
 * Pluggable Text-to-Speech engine interface.
 * Mirrors the SttEngine pattern: init → use → release.
 */
interface ITtsEngine : MemoryManagedComponent {

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
 *
 * [key] is the value actually persisted in `ttsEngineType`, and for Piper that is the models.json
 * engine key — the TTS picker stores whatever `getEngineKeysByType("tts")` returned. Piper's key was
 * previously `"piper"`, which nothing ever wrote: [fromKey] therefore returned null for the stored
 * `"piper_tts"` and TtsManager silently fell back to Android, so selecting Piper never took effect.
 *
 * [aliases] holds spellings that may exist in persisted settings or in a restored backup. Matching
 * them on read is deliberate — rewriting DataStore to normalise would run before the registry loads
 * and has no rollback, whereas an alias costs nothing and keeps old backups importable.
 */
enum class TtsEngineType(val key: String, val aliases: Set<String> = emptySet()) {
    ANDROID("android"),
    PIPER("piper_tts", aliases = setOf("piper"));

    companion object {
        fun fromKey(key: String?): TtsEngineType? =
            entries.find { it.key == key || key in it.aliases }
    }
}
