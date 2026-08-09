package com.voxapps.vision.ui

/** A scan requested by another satellite via [com.voxapps.vision.receiver.OcrScanReceiver].
 *  [imageUri] non-null means "run OCR against this existing image, no camera UI" — see
 *  [com.voxapps.ipc.VoxOcrRequest.imageUri]. [produceOCR] false means capture/crop/stage a photo but
 *  skip the OCR engine entirely — see [com.voxapps.ipc.VoxOcrRequest.produceOCR]. [captureMode]
 *  selects which of Vision's three live-camera capture loops runs — see
 *  [com.voxapps.ipc.VoxOcrRequest.captureMode]. */
data class PendingScanRequest(
    val sourcePackage: String,
    val task: String,
    val hint: String?,
    val returnToCallerOnComplete: Boolean = false,
    val imageUri: String? = null,
    val produceOCR: Boolean = true,
    val captureMode: String = com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_SINGLE
)
