package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.ExpensesAttachments
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxCapabilityClient
import com.voxapps.logging.Logger
import java.io.File

private const val TAG = "MultimodalAttachmentResolver"
private const val EXPENSES_FILE_PROVIDER_AUTHORITY = com.voxapps.expenses.data.ExpensesAttachments.FILE_PROVIDER_AUTHORITY

/**
 * Resolves the staged AI-attachment copy of a receipt image (`filesDir/receipts/<name>_ai.jpg` —
 * Vision's own downscaled copy, see [aiCopyFileName], deliberately *not* the full-resolution
 * `<name>.jpg` used for the receipt record) into a content:// URI Commander can read. Gated on both
 * the caller's own toggle ([attachEnabled] — Expenses' `attachPhotoOnScan`/`attachPhotoOnRetry`
 * settings, checked by the caller before calling this) and the configured engine actually being
 * multimodal. Shared by both entry points into [ExpenseScanCleanupRequestSender]: a fresh scan
 * ([com.voxapps.expenses.receiver.OcrResultReceiver]) and a stub-expense retry
 * ([com.voxapps.expenses.ui.ExpenseEditScreen]'s "Retry cleanup" banner, which reuses the same staged
 * file rather than asking Vision again). OCR text is always sent regardless of this result — see the
 * collapsed voice-command plan's multimodal section for why skipping OCR isn't done here; this only
 * decides whether the photo is *additionally* attached. Fails safe to null on any error (toggle off,
 * unreachable Commander, missing file, grant failure) — identical to today's OCR-text-only behavior.
 */
object MultimodalAttachmentResolver {
    /** `rec_<uuid>.jpg` -> `rec_<uuid>_ai.jpg` — mirrors the existing `.txt` sibling-file convention
     *  ([com.voxapps.expenses.receiver.OcrResultReceiver] already names the OCR-text sibling this way). */
    fun aiCopyFileName(imageName: String): String = imageName.substringBeforeLast('.') + "_ai.jpg"

    suspend fun resolve(context: Context, imageName: String?, attachEnabled: Boolean): String? {
        if (!attachEnabled || imageName == null) return null
        val file = File(File(context.filesDir, ExpensesAttachments.RECEIPTS_DIR), aiCopyFileName(imageName))
        if (!file.exists()) return null // no AI copy was ever staged (Vision's own toggle was off)
        return grantToCommander(context, file)
    }

    /** Same idea as [resolve], but for a manually-added attachment (see :core:attachments) rather
     *  than the original scan's pre-made downscaled sibling — there's no `_ai.jpg` convention for
     *  these, so the file is sent as-is rather than downscaled. Used when the "Retry cleanup" picker
     *  (see [com.voxapps.expenses.ui.ExpenseEditScreen]'s `StubRetryBanner`) resolves to a
     *  manually-added photo instead of the original scan. */
    suspend fun resolveArbitraryFile(context: Context, dirName: String, fileName: String, attachEnabled: Boolean): String? {
        if (!attachEnabled) return null
        val file = File(File(context.filesDir, dirName), fileName)
        if (!file.exists()) return null
        return grantToCommander(context, file)
    }

    private suspend fun grantToCommander(context: Context, file: File): String? {
        if (!VoxCapabilityClient.isMultimodal(context)) return null
        return try {
            val uri = FileProvider.getUriForFile(context, EXPENSES_FILE_PROVIDER_AUTHORITY, file)
            context.grantUriPermission(VoxAppsDiscovery.COMMANDER_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri.toString()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to grant Commander access to the attachment image", e)
            null
        }
    }
}
