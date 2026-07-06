package com.voxapps.commander.service

import com.voxapps.commander.domain.engine.MemoryManagedComponent

interface IWakeWordEngine : MemoryManagedComponent {
    suspend fun initialize(modelPath: String, wakeWord: String): Boolean
    fun startListening(): Boolean
    fun stopListening()
    fun stopService()
    fun release()

    /**
     * Releases the native model (e.g. Vosk Model) to free memory while keeping
     * the engine alive. The model will be re-loaded on the next startListening().
     * Called by the service on memory pressure (onTrimMemory).
     *
     * Unlike [MemoryManagedComponent]'s default no-op, wake word engines are
     * required to implement this explicitly since they always hold a model.
     */
    override fun releaseForMemoryPressure()
}
