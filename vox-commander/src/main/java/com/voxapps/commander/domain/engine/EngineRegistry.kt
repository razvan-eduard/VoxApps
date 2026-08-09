package com.voxapps.commander.domain.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Where the currently live engine of each kind can be found, so its state can be asked for at any
 * time without holding a reference to it.
 *
 * The engines themselves live in three different places with three different lifetimes —
 * `VoiceManager` and `TtsManager` are singletons, `WakeWordService` is a foreground service that
 * dies and restarts — so the UI cannot hold them. It can hold this.
 *
 * [observe] emits [EngineState.Idle] while no engine is registered rather than nothing at all. That
 * is what makes the state queryable at any time: "not loaded" and "does not exist yet" are the same
 * answer to a caller, and neither should be a null it has to handle.
 */
object EngineRegistry {

    /** One slot per kind of engine. The app runs at most one of each at a time. */
    enum class Domain { VOICE, TTS, WAKE }

    private val slots: Map<Domain, MutableStateFlow<VoxEngine?>> =
        Domain.entries.associateWith { MutableStateFlow<VoxEngine?>(null) }

    private fun slot(domain: Domain) = slots.getValue(domain)

    /** Called by whoever owns the engine's lifetime, including with null when it is torn down. */
    fun set(domain: Domain, engine: VoxEngine?) {
        slot(domain).value = engine
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(domain: Domain): Flow<EngineState> =
        slot(domain).flatMapLatest { engine -> engine?.state ?: flowOf(EngineState.Idle) }

    fun current(domain: Domain): EngineState =
        slot(domain).value?.state?.value ?: EngineState.Idle

    /** The engine itself, for the few callers that need to act on it rather than observe it. */
    fun engine(domain: Domain) = slot(domain).asStateFlow()
}
