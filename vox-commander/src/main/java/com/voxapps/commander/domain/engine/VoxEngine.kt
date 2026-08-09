package com.voxapps.commander.domain.engine

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * What every engine is, independently of what it does.
 *
 * The three engine interfaces — [SttEngine], [ITtsEngine], [com.voxapps.commander.service.IWakeWordEngine]
 * — describe what an engine *does*: transcribe, speak, listen for a wake word. None of them
 * described how it *lives*, so each one acquired its model by a different route: the wake-word engine
 * was handed a path, TTS got a language plus a mutable property set from outside, and STT was handed
 * nothing at all and went to read global settings itself. An engine that finds its own files is how
 * knowledge about where a model lives leaks out of the download layer and into three more places,
 * each free to be wrong about it.
 *
 * Implementations extend [BaseVoxEngine] rather than implementing this directly: the parts that must
 * not differ between engines — the load mutex, the state transitions, refusing to unload mid-use —
 * live there and are final.
 */
interface VoxEngine : MemoryManagedComponent {

    /** The `models.json` key this engine answers to. Its identity, not a lookup by extension. */
    val engineKey: String

    /** Observable for the UI. Emits [EngineState.Idle] before anything has been loaded. */
    val state: StateFlow<EngineState>

    /**
     * Makes [spec] the loaded model, replacing whatever was loaded before.
     *
     * There is no separate `reload`: passing a different spec *is* a reload, and passing the same
     * one is a no-op. Concurrent calls are serialised, so two callers racing to prepare the same
     * engine load it once.
     */
    suspend fun load(spec: ModelSpec): Boolean

    /**
     * Releases the model but keeps the engine usable — the next [load] will bring it back.
     * This is what memory pressure wants; platform resources are kept.
     */
    fun unload()

    /** Full teardown, including platform resources (audio tracks, service connections). */
    fun release()
}

/**
 * What an engine needs in order to load, in the shape its runtime actually has.
 *
 * Sealed rather than one class with a nullable `entryPoint`, for the same reason [EngineState] is
 * sealed: a nullable field reintroduces the "unknown" case everywhere it is read, while this makes a
 * cloud engine holding a file path unrepresentable. It is also what gives
 * [com.voxapps.commander.data.remote.EngineRuntime] work to do beyond classification — the runtime
 * decides which of these the caller builds.
 */
sealed interface ModelSpec {
    val language: String

    /**
     * A downloadable model on disk. [entryPoint] is already resolved — the file or directory the
     * engine's library is to be handed — so the engine never scans for it.
     */
    data class LocalModel(
        val modelId: String,
        val entryPoint: File,
        override val language: String
    ) : ModelSpec

    /** Answered over the network. No file; loading means checking it is configured. */
    data class RemoteModel(
        val endpoint: String,
        val credentialRef: String,
        override val language: String
    ) : ModelSpec

    /** Supplied by an OS service, which may or may not be present on this device. */
    data class PlatformModel(
        override val language: String
    ) : ModelSpec

    /**
     * A wake-word engine's configuration, which is a model *and* a phrase.
     *
     * The phrase belongs here rather than in `startListening` because all three engines need it at
     * load time, not at listen time: Porcupine builds a keyword-specific native handle, OpenWakeWord's
     * model *is* the keyword, and Vosk configures its matcher during init. Putting it in the spec is
     * also what makes "the user changed the wake word" expressible as `load(newSpec)` — the base
     * already treats a different spec as a reload and an identical one as a no-op.
     *
     * [entryPoint] is null for a keyword built into the engine, which has nothing on disk.
     */
    data class WakeWordModel(
        val modelId: String?,
        val entryPoint: java.io.File?,
        val keyword: String,
        override val language: String
    ) : ModelSpec
}

/**
 * Where an engine is in its lifecycle, carrying the detail that goes with each position.
 *
 * Sealed with data so illegal combinations cannot be written down. The flat alternative is what
 * engines carry today — `PiperTtsEngine` alone has `ready`, `modelDir`, `currentLanguage`, `stopped`
 * and `isSpeakingNow`, five independent fields that can contradict each other (`ready = true` while
 * the native handle is null). Here [Ready] necessarily has a spec and [Failed] necessarily has a
 * reason.
 *
 * Deliberately says nothing about whether the engine is *busy*: an engine can be [Ready] and
 * mid-inference at the same time. That is a separate axis, guarded by
 * [BaseVoxEngine.withModel], and folding it in here would recreate the contradictory-fields problem
 * under a new name.
 */
sealed interface EngineState {
    data object Idle : EngineState

    data class Loading(
        val spec: ModelSpec,
        val startedAt: Long,
        val progress: Float? = null
    ) : EngineState

    data class Ready(val spec: ModelSpec, val loadedAt: Long) : EngineState

    data class Failed(val spec: ModelSpec?, val reason: String) : EngineState
}
