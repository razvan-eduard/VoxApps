package com.voxapps.commander.service

import com.voxapps.commander.domain.engine.VoxEngine

/**
 * What a wake-word engine does. What it *is* — key, state, load/unload/release — comes from
 * [VoxEngine], and the mechanics from [com.voxapps.commander.domain.engine.BaseVoxEngine].
 *
 * `initialize(modelPath, wakeWord)` is gone: it carried both the model and the phrase as loose
 * strings, and each engine then resolved the path its own way. Both now arrive together in
 * [com.voxapps.commander.domain.engine.ModelSpec.WakeWordModel], already resolved.
 *
 * The explicit `releaseForMemoryPressure()` override that used to live here is gone too. It was
 * declared to force every engine to implement it "since they always hold a model" — but two of the
 * three then implemented it as a deliberate no-op, so the requirement bought nothing. The base
 * releases the model and defers while the engine is in use.
 */
interface IWakeWordEngine : VoxEngine {
    fun startListening(): Boolean
    fun stopListening()
    fun stopService()
}
