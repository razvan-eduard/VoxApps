package com.voxapps.attachments.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc

/**
 * Whether Vox Vision is installed — the one question every attachment capture site asks before
 * offering Vision's cropped-document capture modes. Attaching a photo must never REQUIRE Vision:
 * hosts always list the plain system camera ([rememberCameraCaptureLauncher]) and add the Vision
 * actions (crop-rectangle icon) only when this is true.
 */
@Composable
fun rememberVisionInstalled(): Boolean {
    val context = LocalContext.current
    return remember { VoxAppsDiscovery.isAppInstalled(context, VoxIpc.VISION_PACKAGE) }
}
