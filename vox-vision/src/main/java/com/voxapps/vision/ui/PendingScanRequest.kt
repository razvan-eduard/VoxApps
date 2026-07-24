package com.voxapps.vision.ui

/** A scan requested by another satellite via [com.voxapps.vision.receiver.OcrScanReceiver]. */
data class PendingScanRequest(
    val sourcePackage: String,
    val task: String,
    val hint: String?,
    val returnToCallerOnComplete: Boolean = false
)
