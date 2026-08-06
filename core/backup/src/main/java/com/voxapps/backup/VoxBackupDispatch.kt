package com.voxapps.backup

import android.content.BroadcastReceiver
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            } finally {
                pending.finish()
            }
        }
    }
}
