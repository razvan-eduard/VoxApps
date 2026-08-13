package com.voxapps.attachments

import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest

/**
 * Launches Vision's live camera for an "add an attachment" (or "scan to create a record") capture —
 * the shared entry point every app's capture composable (see [com.voxapps.attachments.ui.rememberVisionCaptureLauncher])
 * routes through, replacing the plain system-camera intent this module used to launch directly. Lives
 * here rather than in `:core:ipc` (which is constants-only, no runtime logic — see [VoxIpc]'s own doc
 * comment) since this module already owns "how an app adds a photo to a record" and is a dependency of
 * every satellite app.
 *
 * Always foreground-triggered (an edit screen, never a background worker), so
 * [VoxOcrRequest.returnToCallerOnComplete] is hardcoded true — mirrors every existing per-app scan
 * sender's own hardcoded choice here.
 */
object VisionAttachmentCapture {
    fun launch(
        context: Context,
        task: String,
        hint: String?,
        produceOCR: Boolean,
        captureMode: String = VoxOcrRequest.CAPTURE_MODE_SINGLE,
        tableMode: Boolean = false
    ) {
        val payload = VoxOcrRequest(
            sourcePackage = context.packageName,
            task = task,
            hint = hint,
            returnToCallerOnComplete = true,
            produceOCR = produceOCR,
            captureMode = captureMode,
            tableMode = tableMode
        ).toJson()
        context.startActivity(
            Intent().apply {
                setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS)
                putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, payload)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
