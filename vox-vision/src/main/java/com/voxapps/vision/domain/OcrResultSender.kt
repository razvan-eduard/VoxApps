package com.voxapps.vision.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrResult

private const val TAG = "OcrResultSender"

/**
 * Replies to a [com.voxapps.ipc.VoxOcrRequest] with the recognized text. Signature-checks the target
 * before sending — same defense-in-depth Commander's `LlmHookWorker` applies before delivering an LLM
 * reply — so a same-signing-key requirement can't be bypassed by a spoofed `sourcePackage`.
 */
object OcrResultSender {
    fun send(context: Context, sourcePackage: String, result: VoxOcrResult) {
        val pm = context.packageManager
        val same = try {
            @Suppress("DEPRECATION")
            pm.checkSignatures(context.packageName, sourcePackage) == PackageManager.SIGNATURE_MATCH
        } catch (e: Exception) {
            false
        }
        if (!same) {
            Log.w(TAG, "Refusing OCR reply — signature mismatch for $sourcePackage")
            return
        }
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_OCR_RESULT)
                .setPackage(sourcePackage)
                .putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, result.toJson())
        )
        Log.d(TAG, "Sent OCR result to $sourcePackage: status=${result.status}")
    }
}
