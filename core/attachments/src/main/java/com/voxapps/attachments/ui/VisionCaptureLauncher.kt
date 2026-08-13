package com.voxapps.attachments.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.voxapps.attachments.VisionAttachmentCapture
import com.voxapps.ipc.VoxOcrRequest

/**
 * Launches Vision's live camera (see [VisionAttachmentCapture]) for one capture session in the
 * given [captureMode] — see [VoxOcrRequest.captureMode] for what single/stitch/batch each mean.
 * Replaces the old system-camera-based `rememberCameraCaptureLauncher`/`rememberBurstCaptureLauncher`.
 *
 * The entire "keep shooting" loop for stitch/batch — capture, crop, (stitch only) continuity-check,
 * offer another shot, Done/Cancel — lives inside Vision itself: this app launches Vision exactly once
 * per session and gets exactly one reply back, handled entirely by the calling app's own
 * `OcrResultReceiver` — never by this composable. There is deliberately no Compose-side waiting/
 * review UI here: Android's own Activity stacking already hides this app's screen for as long as
 * Vision's own capture session is in front, and Vision's own capture-mode bar is what the user
 * actually sees while shooting — this composable's only job is building the right launch request.
 */
@Composable
fun rememberVisionCaptureLauncher(
    baseTask: String,
    hint: String?,
    produceOCR: Boolean,
    captureMode: String = VoxOcrRequest.CAPTURE_MODE_SINGLE,
    tableMode: Boolean = false
): () -> Unit {
    val context = LocalContext.current
    return {
        VisionAttachmentCapture.launch(context, baseTask, hint, produceOCR, captureMode = captureMode, tableMode = tableMode)
    }
}
