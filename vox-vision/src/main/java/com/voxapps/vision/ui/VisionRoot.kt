package com.voxapps.vision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.voxapps.vision.di.VisionContainer

@Composable
fun VisionRoot(
    container: VisionContainer,
    pendingRequest: PendingScanRequest?,
    hasCameraPermission: () -> Boolean,
    requestCameraPermission: ((Boolean) -> Unit) -> Unit,
    finishActivity: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(container = container, onBack = { showSettings = false })
    } else {
        VisionScreen(
            container = container,
            pendingRequest = pendingRequest,
            hasCameraPermission = hasCameraPermission,
            requestCameraPermission = requestCameraPermission,
            onOpenSettings = { showSettings = true },
            finishActivity = finishActivity
        )
    }
}
