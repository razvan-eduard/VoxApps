package com.voxapps.vision

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.voxapps.logging.Logger
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.vision.data.preferences.VisionSettingsRepository
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
        Logger.d("VisionActivity", "onCreate: pendingState=$pendingState")

        setContent {
            val themeDarkMode by container.settingsRepository.themeDarkModeFlow.collectAsStateWithLifecycle(
                initialValue = VisionSettingsRepository.THEME_SYSTEM
            )
            val themeColored by container.settingsRepository.themeColoredFlow.collectAsStateWithLifecycle(initialValue = true)

            CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
                VoxTheme(
                    darkMode = runCatching { VoxDarkMode.valueOf(themeDarkMode) }.getOrDefault(VoxDarkMode.SYSTEM),
                    colored = themeColored
                ) {
                    VisionRoot(
                        container = container,
                        pendingRequest = pendingState,
                        hasCameraPermission = ::hasCameraPermission,
                        requestCameraPermission = ::requestCameraPermission,
                        finishActivity = {
                            pendingState = null // Clear state before finishing
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingState = parsePendingRequest(intent)
        Logger.d("VisionActivity", "onNewIntent: pendingState=$pendingState")
    }

    private fun parsePendingRequest(intent: Intent): PendingScanRequest? =
        VoxOcrRequest.fromJson(intent.getStringExtra(VoxIpc.EXTRA_OCR_PAYLOAD))?.let { request ->
            PendingScanRequest(
                sourcePackage = request.sourcePackage,
                task = request.task,
                hint = request.hint,
                returnToCallerOnComplete = request.returnToCallerOnComplete,
                imageUri = request.imageUri,
                produceOCR = request.produceOCR,
                captureMode = request.captureMode
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
