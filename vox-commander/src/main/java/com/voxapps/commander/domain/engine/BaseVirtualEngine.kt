package com.voxapps.commander.domain.engine

import com.voxapps.logging.Logger

/**
 * An engine with no files: answered over the network, or supplied by an OS service.
 *
 * It manages nothing on disk, but it still has the three things [VoxEngine] describes — a readiness
 * question, a failure reason and a lifecycle — so it belongs under the same contract rather than
 * beside it. What changes is only what "load" means: instead of opening an artefact, it asks whether
 * the engine could run at all. That question is exactly what the scattered `googleSttAvailable` /
 * `geminiIncompatible` flags answer today, each in its own way, each readable only by whoever
 * remembered the flag existed.
 *
 * **A probe is not a request.** [unavailableReason] must not make a real API call: a round-trip at
 * startup costs money, can rate-limit the user, and tells a third party the app launched. It checks
 * what can be checked locally — a credential is present, the OS service exists. An invalid key or a
 * dead endpoint surfaces from an actual call, which is the engine's to report into [failureReason].
 */
abstract class BaseVirtualEngine : BaseVoxEngine() {

    @Volatile
    private var lastReason: String? = null

    /**
     * Null when the engine could run right now; otherwise the reason it could not, in words a
     * settings screen can show.
     */
    protected abstract suspend fun unavailableReason(spec: ModelSpec): String?

    final override suspend fun onLoad(spec: ModelSpec): Boolean {
        val reason = unavailableReason(spec)
        lastReason = reason
        if (reason != null) {
            Logger.log("$engineKey is not available: $reason", TAG)
            return false
        }
        return true
    }

    final override fun failureReason(): String? = lastReason

    /**
     * Nothing to release: the model, if there is one at all, lives in someone else's process.
     * Engines holding a client or a service connection override [onRelease], not this.
     */
    override fun onUnload() {}

    /**
     * Reloading costs a credential check, not a model load, and a fileless engine frees nothing by
     * dropping it — so memory pressure has nothing to gain here.
     */
    override fun releasesUnderMemoryPressure(): Boolean = false

    private companion object {
        const val TAG = "VirtualEngine"
    }
}
