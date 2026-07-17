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
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): VoxResult? = send(context, packageName, VoxCommand(op = VoxIpc.OP_IMPORT, text = payloadJson), timeoutMs)

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
