package com.voxapps.commander.ui.screens.settings

import com.voxapps.onboarding.VoxHintKeys
import com.voxapps.onboarding.VoxHintDialog
import com.voxapps.commander.ui.LocalLanguageManager

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.state.AppStateManager

@Composable
fun PermissionsSettingsTab(

    appStateManager: AppStateManager,
    onRequestMicrophone: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestBatteryOptimization: () -> Unit = {}
) {
        val languageManager = LocalLanguageManager.current
        VoxHintDialog(
            store = appStateManager.hintStoreForUi,
            hintKey = VoxHintKeys.PERMISSIONS,
            title = languageManager.getString("hint_permissions_title"),
            body = languageManager.getString("hint_permissions_body"),
            okLabel = languageManager.getString("hint_ok"),
            dontShowAgainLabel = languageManager.getString("hint_dont_show_again")
        )
    val context = LocalContext.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. Microphone Permission
        PermissionItem(
            title = languageManager.getString("permission_mic_title") ?: "Microphone",
            desc = languageManager.getString("permission_mic_desc") ?: "Required to record your voice commands.",
            isGranted = uiState.hasMicrophonePermission,

            onClick = onRequestMicrophone
        )

        // 2. Notification Permission
        PermissionItem(
            title = languageManager.getString("permission_notif_title") ?: "Notifications",
            desc = languageManager.getString("permission_notif_desc") ?: "Required for background service status.",
            isGranted = uiState.hasNotificationPermission,

            onClick = onRequestNotification
        )

        // 3. System Overlay Permission
        PermissionItem(
            title = languageManager.getString("overlay_permission_title"),
            desc = languageManager.getString("overlay_permission_desc"),
            isGranted = uiState.canDrawOverlays,

            onClick = onRequestOverlay
        )

        // 4. Location Permission (for weather search)
        PermissionItem(
            title = languageManager.getString("permission_location_title") ?: "Location",
            desc = languageManager.getString("permission_location_desc") ?: "Required for weather search provider to get local forecast.",
            isGranted = uiState.hasLocationPermission,

            onClick = onRequestLocation
        )

        // 5. Battery optimization exemption — some OEMs' aggressive background-process killers
        // unbind WakeWordService while backgrounded, silencing wake-word detection. Always
        // clickable (not disabled once granted) — tapping again just confirms via toast rather
        // than re-launching the system dialog.
        PermissionItem(
            title = languageManager.getString("battery_optimization_title") ?: "Battery optimization",
            desc = languageManager.getString("battery_optimization_warning") ?: "Exempt this app so background services keep running reliably.",
            isGranted = uiState.isIgnoringBatteryOptimizations,
            onClick = {
                if (uiState.isIgnoringBatteryOptimizations) {
                    Toast.makeText(context, languageManager.getString("battery_optimization_already_disabled") ?: "Already disabled", Toast.LENGTH_SHORT).show()
                } else {
                    onRequestBatteryOptimization()
                }
            }
        )

        // 6. Query All Packages (normal permission, granted at install)
        PermissionItem(
            title = languageManager.getString("permission_query_packages_title") ?: "Query All Packages",
            desc = languageManager.getString("permission_query_packages_desc") ?: "Required to list installed apps for the Default Apps picker. Granted automatically at install.",
            isGranted = true,

            onClick = {}
        )
    }
}

@Composable
private fun PermissionItem(
    title: String,
    desc: String,
    isGranted: Boolean,

    onClick: () -> Unit
) {
        val languageManager = LocalLanguageManager.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = if (isGranted)
                        languageManager.getString("overlay_permission_granted")
                    else
                        languageManager.getString("overlay_permission_required")
                )
            }
        }
    }
}
