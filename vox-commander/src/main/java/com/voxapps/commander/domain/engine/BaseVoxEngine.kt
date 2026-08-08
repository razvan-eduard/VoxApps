package com.voxapps.commander.domain.engine

import com.voxapps.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The mechanics every engine needs and none should write for itself.
 *
 * Template Method, the same shape [com.voxapps.commander.receiver.VoxExportImportHandler] already
 * uses: the orchestration here is `final`, and an engine supplies only the two steps that genuinely
 * differ — [onLoad] and [onUnload].
 *
 * The load mutex in particular is deliberately *not* an abstract member. Leaving it to each engine
 * is what produced the current situation, where `WhisperCppSttEngine` has a `loadMutex` and
 * `VoskSttEngine.ensureModelLoaded` has no synchronisation at all: two callers can enter its
 * check-then-act and load the same model twice. Because [load] is final, an engine cannot bypass it.
 */
abstract class BaseVoxEngine : VoxEngine {

    private val loadMutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    final override val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Guards against unloading underneath a running inference — see [withModel]. */
    private var inUse = 0
    private val useLock = Any()

    final override suspend fun load(spec: ModelSpec): Boolean = loadMutex.withLock {
        val current = _state.value
        if (current is EngineState.Ready && current.spec == spec) {
            // Same model already loaded. This is what makes "reload" unnecessary as a separate
            // operation: callers can hand over their current selection unconditionally.
            return@withLock true
        }
        if (current is EngineState.Ready) {
            Logger.log("$engineKey: switching model, releasing ${current.spec}", TAG)
            doUnload()
        }

        _state.value = EngineState.Loading(spec, System.currentTimeMillis())
        val ok = try {
            onLoad(spec)
        } catch (e: CancellationException) {
            // The caller abandoned the load; that is not an engine failure and must not be recorded
            // as one, or the next caller would see Failed for something that never went wrong.
            _state.value = EngineState.Idle
            throw e
        } catch (e: Exception) {
            Logger.log("$engineKey: load failed: ${e.message}", TAG)
            _state.value = EngineState.Failed(spec, e.message ?: e.javaClass.simpleName)
            return@withLock false
        }

        _state.value = if (ok) {
            EngineState.Ready(spec, System.currentTimeMillis())
        } else {
            EngineState.Failed(spec, "engine reported failure")
        }
        ok
    }

    final override fun unload() {
        synchronized(useLock) {
            if (inUse > 0) {
                // Tearing down native state under a running inference fails in the native layer
                // rather than throwing something catchable. Memory pressure is a request, not an
                // order — the model is released on the next attempt instead.
                Logger.log("$engineKey: unload skipped, engine is in use", TAG)
                return
            }
        }
        if (_state.value is EngineState.Idle) return
        doUnload()
        _state.value = EngineState.Idle
    }

    final override fun release() {
        doUnload()
        _state.value = EngineState.Idle
        onRelease()
    }

    final override fun releaseForMemoryPressure() {
        if (releasesUnderMemoryPressure()) unload()
    }

    /**
     * Whether this engine should give its model back when the system asks for memory.
     *
     * Default yes. An engine overrides it when it has a real reason not to — a model small enough
     * that reloading costs more than it frees, or a flow in progress that a release would break
     * even though no call is currently inside [withModel]. It is a reason to decline, not a place
     * to reimplement the release: what happens when the answer is yes stays final.
     */
    protected open fun releasesUnderMemoryPressure(): Boolean = true

    private fun doUnload() {
        try {
            onUnload()
        } catch (e: Exception) {
            Logger.log("$engineKey: unload failed: ${e.message}", TAG)
        }
    }

    /**
     * Runs [block] with the model pinned, so a concurrent [unload] defers instead of pulling it out
     * from underneath. Every engine needs this and each currently invents it: `VoskSttEngine` has an
     * `isTranscribing` flag, `WhisperCppSttEngine` has one with a delicate read order documented in
     * a comment, and `OpenWakeWordEngine`'s equivalent is not even `@Volatile`.
     */
    protected suspend fun <R> withModel(block: suspend () -> R): R {
        synchronized(useLock) { inUse++ }
        try {
            return block()
        } finally {
            synchronized(useLock) { inUse-- }
        }
    }

    /** Reported into [EngineState.Loading]; the engine is the only thing that knows its progress. */
    protected fun reportProgress(fraction: Float) {
        val current = _state.value
        if (current is EngineState.Loading) {
            _state.value = current.copy(progress = fraction.coerceIn(0f, 1f))
        }
    }

    /** Load [spec]. Return false for an expected failure; throw for an unexpected one. */
    protected abstract suspend fun onLoad(spec: ModelSpec): Boolean

    /** Release the model. Must be safe to call when nothing is loaded. */
    protected abstract fun onUnload()

    /** Release platform resources beyond the model. Only called from [release]. */
    protected open fun onRelease() {}

    private companion object {
        const val TAG = "VoxEngine"
    }
}
