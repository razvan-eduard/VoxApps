package com.voxapps.backup

import android.content.BroadcastReceiver
import com.voxapps.ipc.VoxResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "VoxBackupDispatch"

/**
 * The `goAsync()` + `CoroutineScope(Dispatchers.IO).launch { try { pending.setResultData(...) }
 * finally { pending.finish() } }` glue duplicated in every `VoxCommandReceiver.onReceive()`'s
 * `OP_EXPORT`/`OP_IMPORT` branches (8 near-identical copies across the 4 apps). Scoped to exactly
 * those two ops — the same shape exists for `OP_READ`/`OP_SYNC_EXPORT`/etc. too, left untouched to
 * keep this refactor's blast radius to the backup path.
 *
 * Must use the [android.content.BroadcastReceiver.PendingResult]'s own `setResultData`, not the
 * inherited `BroadcastReceiver.setResult()` — the latter throws once called outside `onReceive()`'s
 * synchronous window, which `goAsync()`'s whole point is to let this do.
 */
object VoxBackupDispatch {
    fun dispatch(receiver: BroadcastReceiver, block: suspend () -> VoxResult) {
        val pending = receiver.goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                pending.setResultData(block().toJson())
            } catch (e: Exception) {
                // [block] parses peer-supplied JSON and writes to Room, so it genuinely can throw
                // (malformed payload, constraint violation). Without this catch the exception
                // escapes an ad-hoc scope that has no CoroutineExceptionHandler, reaching the
                // default uncaught handler and killing the whole app process — while the caller
                // just sees a timeout with no idea why. Answer with a failed result instead.
                Logger.w(TAG, "Backup IPC failed", e)
                runCatching { pending.setResultData(VoxResult(ok = false, text = e.message ?: "Backup operation failed").toJson()) }
            } finally {
                pending.finish()
            }
        }
    }
}
