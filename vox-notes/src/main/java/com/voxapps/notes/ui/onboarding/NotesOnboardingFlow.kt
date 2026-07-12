package com.voxapps.notes.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.voxapps.notes.domain.localization.LanguageManager
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.onboarding.OnboardingPermission
import com.voxapps.onboarding.OnboardingPermissionsScreen
import com.voxapps.onboarding.OnboardingWelcomeScreen

/**
 * First-launch welcome + permission flow, gated by [com.voxapps.notes.data.preferences.NotesSettings.onboardingCompleted].
 * Lightweight by design (single welcome page, not a multi-page tutorial) — mirrors vox-commander's
 * onboarding concept without its settings-tab-driven depth.
 */
@Composable
fun NotesOnboardingFlow(languageManager: LanguageManager, stateManager: NotesStateManager) {
    val context = LocalContext.current
    val needsNotifPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notifGranted by remember {
        mutableStateOf(
            !needsNotifPermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    var showPermissions by remember { mutableStateOf(false) }

    if (!showPermissions) {
        OnboardingWelcomeScreen(
            icon = Icons.Filled.EditNote,
            appName = languageManager.getString("app_name"),
            tagline = languageManager.getString("onboarding_welcome_tagline"),
            bullets = listOf(
                languageManager.getString("onboarding_bullet1_title") to
                    languageManager.getString("onboarding_bullet1_desc"),
                languageManager.getString("onboarding_bullet2_title") to
                    languageManager.getString("onboarding_bullet2_desc"),
                languageManager.getString("onboarding_bullet3_title") to
                    languageManager.getString("onboarding_bullet3_desc")
            ),
            continueLabel = languageManager.getString("continue_button"),
            onContinue = {
                if (needsNotifPermission) showPermissions = true
                else stateManager.setOnboardingCompleted(true)
            }
        )
    } else {
        OnboardingPermissionsScreen(
            title = languageManager.getString("onboarding_permissions_title"),
            intro = languageManager.getString("onboarding_permissions_intro"),
            permissions = listOf(
                OnboardingPermission(
                    title = languageManager.getString("permission_notif_title"),
                    description = languageManager.getString("permission_notif_desc"),
                    granted = notifGranted,
                    onRequest = { requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
            ),
            grantedLabel = languageManager.getString("permission_status_granted"),
            requiredLabel = languageManager.getString("permission_status_required"),
            continueLabel = languageManager.getString("continue_button"),
            onContinue = { stateManager.setOnboardingCompleted(true) }
        )
    }
}
