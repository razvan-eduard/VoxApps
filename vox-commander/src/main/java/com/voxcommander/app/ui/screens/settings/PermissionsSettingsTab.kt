package com.voxcommander.app.ui.screens.settings

import com.voxcommander.app.ui.LocalLanguageManager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.state.AppStateManager

@Composable
fun PermissionsSettingsTab(

    appStateManager: AppStateManager,
    onRequestMicrophone: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestLocation: () -> Unit
) {
        val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = languageManager.getString("permissions_section") ?: "System Permissions",
            style = MaterialTheme.typography.titleMedium
        )

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
            title = "Location",
            desc = "Required for weather search provider to get local forecast.",
            isGranted = uiState.hasLocationPermission,

            onClick = onRequestLocation
        )

        // 5. Query All Packages (normal permission, granted at install)
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
