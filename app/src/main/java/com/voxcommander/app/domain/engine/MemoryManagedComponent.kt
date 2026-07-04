package com.voxcommander.app.domain.engine

/**
 * Contract for components that may hold heavy native resources (models, native
 * contexts, GPU delegates) which should be released under system memory pressure
 * (Application/Service onTrimMemory) without tearing down the whole component.
 *
 * Implementations must be able to transparently reload the released resource on
 * next use (lazy re-init), and must guard against releasing while actively in use
 * (e.g. mid-transcription/mid-generation) to avoid crashing an in-flight native call.
 *
 * Default implementation is a no-op, so lightweight components (API-based engines,
 * system TTS, etc.) don't need to override this.
 */
interface MemoryManagedComponent {
    fun releaseForMemoryPressure() { /* no-op by default */ }
}
