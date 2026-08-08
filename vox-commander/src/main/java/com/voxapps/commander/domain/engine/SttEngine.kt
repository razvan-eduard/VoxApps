package com.voxapps.commander.domain.engine

/**
 * What a speech-to-text engine does. What it *is* — key, state, load/unload/release — comes from
 * [VoxEngine], and the mechanics of living come from [BaseVoxEngine].
 *
 * The two-stage `releaseHardware()` / `releaseResources()` contract that used to live here is gone.
 * Nothing ever called the stages separately — `release()` invoked both, in order, and was its only
 * caller — so it was a contract no caller exercised and every engine had to implement twice.
 * [BaseVoxEngine.onUnload] is the one hook, and the base decides when it runs.
 */
interface SttEngine : VoxEngine {

    /**
     * Transcribes audio data to text.
     *
     * [langCode] is null for auto-detection. Engines that cannot be told a language ignore it; the
     * caller used to ask `is WhisperSttEngine || is WhisperCppSttEngine` to decide whether passing
     * one was possible, which is a type test standing in for a capability.
     */
    suspend fun transcribe(audio: ByteArray, langCode: String? = null): String

    /**
     * Processes a chunk of audio and returns a partial transcription if available.
     */
    suspend fun processChunk(audio: ByteArray): String? = null
}
