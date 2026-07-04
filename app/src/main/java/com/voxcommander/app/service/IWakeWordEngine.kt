package com.voxcommander.app.service

interface IWakeWordEngine {
    suspend fun initialize(modelPath: String, wakeWord: String): Boolean
    fun startListening(): Boolean
    fun stopListening()
    fun stopService()
    fun release()

    /**
     * Releases the native model (e.g. Vosk Model) to free memory while keeping
     * the engine alive. The model will be re-loaded on the next startListening().
     * Called by the service on memory pressure (onTrimMemory).
     */
    fun releaseModelForMemoryPressure()
}
