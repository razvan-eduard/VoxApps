package com.voxapps.attachments.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Returns a callback that launches the system camera app and hands the captured photo to
 * [onCaptured] as a `content://` URI — ready to pass straight into [com.voxapps.attachments.AttachmentFileStore.stage],
 * which already accepts any source URI. Centralized here (rather than duplicated per app) since
 * every attachment-owning app needs identical plumbing: CAMERA runtime permission, a temp file
 * under `cacheDir/camera/`, and the FileProvider URI that file needs to be handed to the camera app.
 *
 * [authority] is the caller's own FileProvider authority (each app already declares one for
 * attachment access) — this module never declares its own `<provider>`, same convention as
 * [com.voxapps.attachments.AttachmentFileStore].
 */
@Composable
fun rememberCameraCaptureLauncher(authority: String, onCaptured: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingUri
        val file = pendingFile
        pendingUri = null
        pendingFile = null
        if (success && uri != null) onCaptured(uri)
        // Only a staging area — AttachmentFileStore.stage() (called from onCaptured) already copied
        // it into the real attachments dir under its own filename by the time this runs.
        file?.delete()
    }

    fun launchCapture() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, authority, file)
        pendingFile = file
        pendingUri = uri
        takePicture.launch(uri)
    }

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapture()
    }

    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCapture()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }
}
