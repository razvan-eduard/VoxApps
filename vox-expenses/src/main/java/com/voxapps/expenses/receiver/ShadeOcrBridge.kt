package com.voxapps.expenses.receiver

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.logging.Logger
import java.io.FileOutputStream

private const val TAG = "ShadeOcrBridge"

/**
 * The screenshot-and-OCR half of the hybrid recovery — the fallback for when a stricter OEM strips
 * the sensitive lines from the accessibility tree but still renders them.
 *
 * The captured shade is staged as a file and handed to Vision on the same headless OCR bus every
 * scan rides; the reply is not awaited here but correlated back by task, since the stubs it fills
 * live in the pending store and outlast any callback — see [OcrResultReceiver]'s [LlmTasks.SHADE_OCR]
 * branch. Nothing is created: the recognised text only completes stubs already in review.
 */
object ShadeOcrBridge {

    /** The staged screenshot's name — fixed, so one recovery's file is overwritten by the next and
     *  the reply handler knows what to clean up without carrying state. */
    const val SHADE_FILE = "shade_ocr.jpg"

    fun recognize(context: Context, shade: Bitmap) {
        val target = AttachmentFileStore.file(context, ExpensesAttachments.DIR, SHADE_FILE)
        val staged = runCatching {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { shade.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }.isSuccess
        if (!staged) {
            Logger.w(TAG, "could not stage shade screenshot")
            return
        }
        val uri = AttachmentFileStore.uriFor(
            context, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, ExpensesAttachments.DIR, SHADE_FILE
        )
        context.grantUriPermission(VoxIpc.VISION_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.SHADE_OCR,
            returnToCallerOnComplete = false,
            imageUri = uri.toString(),
            skipCrop = true,
            // A shade is free-flowing text, not a table — the table reader would fight its layout.
            tableMode = false
        ).toJson()
        Logger.d(TAG, "handing shade screenshot to Vision for OCR fallback")
        runCatching {
            context.startActivity(
                Intent().apply {
                    setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS)
                    putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, payload)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { Logger.w(TAG, "Vision not reachable for OCR fallback: ${it.message}") }
    }

    /** Takes the staged screenshot back out once its text has been read (or the read failed). */
    fun cleanup(context: Context) {
        AttachmentFileStore.delete(context, ExpensesAttachments.DIR, SHADE_FILE)
    }
}
