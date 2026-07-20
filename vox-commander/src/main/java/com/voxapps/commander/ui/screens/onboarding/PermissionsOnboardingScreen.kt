package com.voxapps.commander.ui.screens.onboarding

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.ui.LocalLanguageManager

/**
 * First-run permission step (shown once after the tutorial). Walks the user through the essentials so
 * the floating overlay and voice capture actually work — otherwise a fresh install silently fails
 * because the overlay is only shown when `Settings.canDrawOverlays()` is true (WakeWordService).
 * Wired to MainActivity's existing permission launchers; status updates live from AppState.
 */
@Composable
fun PermissionsOnboardingScreen(
    appStateManager: AppStateManager,
    onRequestOverlay: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onContinue: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    Scaffold { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = languageManager.getString("onboarding_permissions_title") ?: "Permissions",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = languageManager.getString("onboarding_permissions_intro")
                    ?: "Grant these so the voice assistant and its floating microphone work anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Overlay — required for the floating mic over other apps
            PermissionRow(
                title = languageManager.getString("overlay_permission_title"),
                desc = languageManager.getString("overlay_permission_desc"),
                isGranted = uiState.canDrawOverlays,
                onClick = onRequestOverlay
            )

            // Microphone — required to capture voice
            PermissionRow(
                title = languageManager.getString("permission_mic_title") ?: "Microphone",
                desc = languageManager.getString("permission_mic_desc") ?: "Required to record your voice commands.",
                isGranted = uiState.hasMicrophonePermission,
                onClick = onRequestMicrophone
            )

            // Notifications — recommended (foreground service status), API 33+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRow(
                    title = languageManager.getString("permission_notif_title") ?: "Notifications",
                    desc = languageManager.getString("permission_notif_desc") ?: "Required for background service status.",
                    isGranted = uiState.hasNotificationPermission,
                    onClick = onRequestNotification
                )
            }

            // Location — needed by the weather search provider (WeatherAPI/Open-Meteo)
            PermissionRow(
                title = languageManager.getString("permission_location_title") ?: "Location",
                desc = languageManager.getString("permission_location_desc")
                    ?: "Required for weather search provider to get local forecast.",
                isGranted = uiState.hasLocationPermission,
                onClick = onRequestLocation
            )

            // Battery optimization exemption — some OEMs' aggressive background-process killers
            // unbind WakeWordService while backgrounded, silencing wake-word detection. Always
            // clickable (not disabled once granted) — tapping again just confirms via toast rather
            // than re-launching the system dialog.
            PermissionRow(
                title = languageManager.getString("battery_optimization_title") ?: "Battery optimization",
                desc = languageManager.getString("battery_optimization_warning")
                    ?: "Exempt this app so background services keep running reliably.",
                isGranted = uiState.isIgnoringBatteryOptimizations,
                onClick = {
                    if (uiState.isIgnoringBatteryOptimizations) {
                        Toast.makeText(
                            context,
                            languageManager.getString("battery_optimization_already_disabled") ?: "Already disabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        onRequestBatteryOptimization()
                    }
                },
                alwaysClickable = true
            )

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(languageManager.getString("continue_button") ?: "Continue")
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    isGranted: Boolean,
    onClick: () -> Unit,
    // Most permission rows disable the button once granted (nothing left to confirm). Battery
    // optimization stays clickable even when granted — tapping again shows a toast instead of
    // re-launching the system dialog, matching the persistent green/red pattern used elsewhere
    // (e.g. Vox Expenses' notification-access button).
    alwaysClickable: Boolean = false
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
                enabled = alwaysClickable || !isGranted,
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
