package com.voxcommander.app.domain.engine

interface SttEngine : MemoryManagedComponent {
    /**
     * Transcribes audio data to text.
     */
    suspend fun transcribe(audio: ByteArray): String

    /**
     * Processes a chunk of audio and returns a partial transcription if available.
     */
    suspend fun processChunk(audio: ByteArray): String? = null

    /**
     * Template method for releasing the engine.
     * Enforces a 2-stage cleanup: Hardware (JNI/GPU) then Resources (Memory).
     */
    fun release() {
        releaseHardware()
        releaseResources()
    }

    /**
     * Stage 1: Explicitly free native pointers, close JNI contexts, or stop GPU usage.
     */
    fun releaseHardware()

    /**
     * Stage 2: Nullify Kotlin/Java references and clear memory buffers.
     */
    fun releaseResources()
}
