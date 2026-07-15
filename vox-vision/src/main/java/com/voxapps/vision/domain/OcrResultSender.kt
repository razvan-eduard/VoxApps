package com.voxapps.vision.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrResult

private const val TAG = "OcrResultSender"

/**
 * Replies to a [com.voxapps.ipc.VoxOcrRequest] with the recognized text and optional image URI.
 * Grants read permission to the target package for the shared receipt image.
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
            Logger.w(TAG, "Refusing OCR reply — signature mismatch for $sourcePackage")
            return
        }
        
        val intent = Intent(VoxIpc.ACTION_OCR_RESULT)
            .setPackage(sourcePackage)
            .putExtra(VoxIpc.EXTRA_OCR_PAYLOAD, result.toJson())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        
        // Android permission grants (FLAG_GRANT_READ_URI_PERMISSION) only apply to URIs in the Data
        // field or ClipData — they don't peek into extras strings. We duplicate the URI in ClipData
        // purely to satisfy the permission system; OcrResultReceiver still reads the full payload.
        result.imageUri?.let { uriString ->
            try {
                val uri = uriString.toUri()
                // Explicitly grant read permission to the target app. This is more reliable for 
                // cross-app broadcasts than relying solely on FLAG_GRANT_READ_URI_PERMISSION.
                context.grantUriPermission(sourcePackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = android.content.ClipData.newRawUri("receipt_image", uri)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to grant permission for URI: $uriString", e)
            }
        }
            
        context.sendBroadcast(intent)
        Logger.d(TAG, "Sent OCR result to $sourcePackage: status=${result.status}")
    }
}
