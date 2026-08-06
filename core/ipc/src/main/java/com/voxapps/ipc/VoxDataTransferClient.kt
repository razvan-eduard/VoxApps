package com.voxapps.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Vox Hub's data-portability client: requests a satellite's full settings+data as JSON
 * ([requestExport]) or pushes a previously-exported JSON blob back into one ([requestImport]).
 * Same ordered-broadcast request/reply shape as [VoxAppsDiscovery.ping], but with a longer timeout —
 * serializing/writing a full notes or expenses database is heavier than a plain handshake.
 */
object VoxDataTransferClient {

    private const val DEFAULT_TIMEOUT_MS = 10_000L

    suspend fun requestExport(
        context: Context,
        packageName: String,
        scope: String = VoxIpc.EXPORT_SCOPE_BOTH,
        includeSecrets: Boolean = false,
        includePhotos: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): VoxResult? = send(
        context, packageName,
        VoxCommand(op = VoxIpc.OP_EXPORT, exportScope = scope, includeSecrets = includeSecrets, includePhotos = includePhotos),
        timeoutMs
    )

    suspend fun requestImport(
        context: Context,
        packageName: String,
        payloadJson: String,
        importMode: String = VoxIpc.IMPORT_MODE_MERGE,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): VoxResult? = send(
        context, packageName,
        VoxCommand(op = VoxIpc.OP_IMPORT, text = payloadJson, importMode = importMode),
        timeoutMs
    )

    /**
     * Peer-to-peer sync, export side: asks a satellite for everything that's changed since [since]
     * (0/null for a first-ever sync with this peer), optionally restricted to [scopeNames]
     * (category/layer names — null/empty means everything). See [VoxIpc.OP_SYNC_EXPORT].
     */
    suspend fun requestSyncExport(
        context: Context,
        packageName: String,
        since: Long?,
        scopeNames: List<String>? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): VoxResult? = send(
        context, packageName,
        VoxCommand(op = VoxIpc.OP_SYNC_EXPORT, since = since, scopeNames = scopeNames),
        timeoutMs
    )

    /**
     * Peer-to-peer sync, merge side: hands a satellite a delta ([deltaJson], the same shape
     * [requestSyncExport] returns) to apply via insert-if-new / last-write-wins-by-updatedAt /
     * delete-on-tombstone — never [requestImport]'s wipe-and-replace. See [VoxIpc.OP_SYNC_MERGE].
     */
    suspend fun requestSyncMerge(
        context: Context,
        packageName: String,
        deltaJson: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): VoxResult? = send(context, packageName, VoxCommand(op = VoxIpc.OP_SYNC_MERGE, text = deltaJson), timeoutMs)

    /**
     * Fetches a satellite's [VoxSatelliteSchema] — its call-count contract, prompt template, and
     * schema version — over the same request-response channel as [requestExport]/[requestImport].
     * Called from Commander's Integrations "Refresh" button (proactive, cached), not per voice
     * command. Returns null on timeout, unreachable satellite, or a malformed/missing-contract reply.
     */
    suspend fun requestSchema(
        context: Context,
        packageName: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): VoxSatelliteSchema? {
        val result = send(context, packageName, VoxCommand(op = VoxIpc.OP_GET_SCHEMA), timeoutMs)
        if (result?.ok != true) return null
        return VoxSatelliteSchema.fromJson(result.text)
    }

    /**
     * Day-scoped read: reuses the existing [VoxIpc.OP_READ] channel with [VoxCommand.dateFrom]/
     * [VoxCommand.dateTo] set, rather than a new op — a satellite that doesn't understand day-scoped
     * reads just ignores the extra fields and returns its normal full-snapshot text. Used by Vox
     * Calendar's day-tap summary to ask Notes/Expenses for that day's records.
     */
    suspend fun requestDayRead(
        context: Context,
        packageName: String,
        dateFrom: Long,
        dateTo: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): VoxResult? = send(context, packageName, VoxCommand(op = VoxIpc.OP_READ, dateFrom = dateFrom, dateTo = dateTo), timeoutMs)

    /**
     * Generic escape hatch: sends an arbitrary [VoxCommand] as-is and returns the raw [VoxResult] —
     * used by callers (the VoxConnect Bridge) that need to forward a command built elsewhere rather
     * than construct one of the named shapes above. Every named function in this object could be
     * written in terms of this one; they aren't, purely so each call site keeps its specific
     * field-by-field signature instead of a raw [VoxCommand].
     */
    suspend fun sendCommand(
        context: Context,
        packageName: String,
        command: VoxCommand,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): VoxResult? = send(context, packageName, command, timeoutMs)

    /**
     * Fire-and-forget push: a satellite calls this the instant its own dynamic context (categories,
     * currency, language, or equivalent) changes, so Commander's cache is corrected immediately
     * instead of waiting for a manual Refresh. Not a request-response — no reply is expected or
     * awaited. See [VoxIpc.ACTION_SCHEMA_CHANGED].
     */
    fun pushSchemaChanged(context: Context, schema: VoxSatelliteSchema) {
        val intent = Intent(VoxIpc.ACTION_SCHEMA_CHANGED).apply {
            setPackage(VoxAppsDiscovery.COMMANDER_PACKAGE)
            putExtra(VoxIpc.EXTRA_SCHEMA_PAYLOAD, schema.toJson())
            putExtra(VoxIpc.EXTRA_SOURCE_PACKAGE, context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private suspend fun send(context: Context, packageName: String, command: VoxCommand, timeoutMs: Long): VoxResult? {
        val intent = Intent(VoxIpc.ACTION_COMMAND).apply {
            setPackage(packageName)
            putExtra(VoxIpc.EXTRA_PAYLOAD, command.toJson())
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
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
        }
    }
}
