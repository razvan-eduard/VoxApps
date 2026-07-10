package com.voxapps.vision

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.vision.ui.LocalLanguageManager
import com.voxapps.vision.ui.PendingScanRequest
import com.voxapps.vision.ui.VisionRoot

/**
 * Standalone launcher, mirrors vox-notes' NotesActivity shape (minus the biometric gate — Vision
 * holds no persisted sensitive data to lock). Also the direct-launch target when another satellite
 * asks Vision to scan on its behalf (see [VoxIpc.VISION_ACTIVITY_CLASS]) — the caller is foreground
 * itself when it does this, so no notification/background-activity-launch detour is needed.
 *
 * If Vision is already running (e.g. left open from a previous standalone session), a repeat launch
 * brings the *existing* instance to front via [onNewIntent] rather than a fresh [onCreate] — confirmed
 * on-device: without overriding onNewIntent (and without `launchMode="singleTask"` in the manifest),
 * the new intent's pending-request extras were silently dropped and the already-running standalone
 * instance just resurfaced unchanged. Pending state is therefore held in Compose state, re-parsed on
 * both onCreate and onNewIntent.
 */
class VisionActivity : ComponentActivity() {

    private val container by lazy { (application as VisionApplication).container }
    private var pendingState by mutableStateOf<PendingScanRequest?>(null)

    private var onPermissionResult: ((Boolean) -> Unit)? = null
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onPermissionResult?.invoke(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingState = parsePendingRequest(intent)
        Log.d("VisionActivity", "onCreate: pendingState=$pendingState")

        setContent {
            CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
                VoxTheme(darkMode = VoxDarkMode.SYSTEM, colored = true) {
                    VisionRoot(
                        container = container,
                        pendingRequest = pendingState,
                        hasCameraPermission = ::hasCameraPermission,
                        requestCameraPermission = ::requestCameraPermission,
                        finishActivity = { finish() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingState = parsePendingRequest(intent)
        Log.d("VisionActivity", "onNewIntent: pendingState=$pendingState")
    }

    private fun parsePendingRequest(intent: Intent): PendingScanRequest? =
        VoxOcrRequest.fromJson(intent.getStringExtra(VoxIpc.EXTRA_OCR_PAYLOAD))?.let { request ->
            PendingScanRequest(
                sourcePackage = request.sourcePackage,
                task = request.task,
                hint = request.hint
            )
        }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission(onResult: (Boolean) -> Unit) {
        onPermissionResult = onResult
        requestCameraPermission.launch(android.Manifest.permission.CAMERA)
    }
}
