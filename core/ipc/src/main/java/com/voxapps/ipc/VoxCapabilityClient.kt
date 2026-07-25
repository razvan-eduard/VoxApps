package com.voxapps.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.voxapps.logging.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Queries Commander's currently-configured LLM engine for its capabilities — global engine state, not
 * per-satellite data, which is why this is kept separate from [VoxSatelliteSchema]/[VoxIpc.OP_GET_SCHEMA]
 * on purpose. Callable directly by any first-party app (e.g. Vision, before deciding whether to attach
 * a scanned photo to its extraction request). No caching here: it's a cheap, purely local read on
 * Commander's side, not a cross-process fetch with a staleness problem, so a fresh query per call is
 * fine and simpler than inventing an invalidation story for it.
 */
object VoxCapabilityClient {

    private const val DEFAULT_TIMEOUT_MS = 3_000L
    private const val KEY_MULTIMODAL = "multimodal"
    private const val KEY_LOCAL = "local"

    /**
     * [local] fails safe to `true` (not `false`) on timeout/unreachable, unlike [multimodal] — an
     * inconclusive probe should make a caller pick the more defensive, small-model-tuned behavior
     * (e.g. [com.voxapps.expenses.domain.llm.NotificationExpenseParsePromptBuilder]'s local-engine
     * prompt variant) rather than assume a capable remote model that may not actually be there.
     */
    data class EngineCapabilities(val multimodal: Boolean, val local: Boolean)

    private suspend fun query(context: Context, timeoutMs: Long): EngineCapabilities {
        val intent = Intent(VoxIpc.ACTION_CAPABILITY_QUERY).apply {
            setPackage(VoxAppsDiscovery.COMMANDER_PACKAGE)
        }
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<VoxResult?> { cont ->
                context.sendOrderedBroadcast(
                    intent,
                    null,
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context, i: Intent) {
                            if (cont.isActive) cont.resume(VoxResult.fromJson(resultData))
                        }
                    },
                    null,
                    0,
                    null,
                    null as Bundle?
                )
            }
        } ?: run {
            Logger.d("VoxCapabilityClient", "Capability query timed out or Commander unreachable")
            return EngineCapabilities(multimodal = false, local = true)
        }
        if (result?.ok != true) return EngineCapabilities(multimodal = false, local = true)
        return try {
            val o = org.json.JSONObject(result.text)
            EngineCapabilities(
                multimodal = o.optBoolean(KEY_MULTIMODAL, false),
                local = o.optBoolean(KEY_LOCAL, true)
            )
        } catch (e: Exception) {
            EngineCapabilities(multimodal = false, local = true)
        }
    }

    /**
     * Fails safe to `false` on timeout or if Commander is unreachable — this check is meant to be an
     * optimization (attach a photo when possible), not a requirement, so callers must never block or
     * retry on it; a failed query is indistinguishable from "not multimodal" by design.
     */
    suspend fun isMultimodal(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        query(context, timeoutMs).multimodal

    /** See [EngineCapabilities.local] for the fail-safe direction (defaults to `true`, not `false`). */
    suspend fun isLocalEngine(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        query(context, timeoutMs).local

    /** Commander's own receiver builds its reply with this — kept here so both sides agree on shape. */
    fun buildReply(multimodal: Boolean, local: Boolean): VoxResult =
        VoxResult(
            ok = true,
            text = org.json.JSONObject().put(KEY_MULTIMODAL, multimodal).put(KEY_LOCAL, local).toString()
        )
}
