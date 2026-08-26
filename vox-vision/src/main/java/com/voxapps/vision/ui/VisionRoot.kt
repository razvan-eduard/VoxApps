package com.voxapps.vision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.voxapps.vision.di.VisionContainer
import com.voxapps.vision.ui.screens.splash.SplashLoadingScreen

@Composable
fun VisionRoot(
    container: VisionContainer,
    liveView: Boolean,
    pendingRequest: PendingScanRequest?,
    hasCameraPermission: () -> Boolean,
    requestCameraPermission: ((Boolean) -> Unit) -> Unit,
    finishActivity: () -> Unit
) {
    var showSplash by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSplash) {
        SplashLoadingScreen(onFinished = { showSplash = false })
    } else if (showSettings) {
        SettingsScreen(container = container, onBack = { showSettings = false })
    } else if (liveView) {
        LiveViewScreen(
            container = container,
            hasCameraPermission = hasCameraPermission,
            requestCameraPermission = requestCameraPermission,
            onOpenSettings = { showSettings = true }
        )
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
