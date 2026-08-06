package com.voxapps.expenses.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.onboarding.OnboardingPermission
import com.voxapps.onboarding.OnboardingPermissionsScreen
import com.voxapps.onboarding.OnboardingWelcomeScreen

/**
 * First-launch welcome + permission flow, gated by
 * [com.voxapps.expenses.data.preferences.ExpensesSettings.onboardingCompleted]. Lightweight by
 * design (single welcome page, not a multi-page tutorial) — mirrors vox-notes' NotesOnboardingFlow.
 */
@Composable
fun ExpensesOnboardingFlow(languageManager: LanguageManager, stateManager: ExpensesStateManager) {
    val context = LocalContext.current
    val needsNotifPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notifGranted by remember {
        mutableStateOf(
            !needsNotifPermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val requestNotifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    // Coarse is sufficient (and what's actually requested) for a city-level location prefill —
    // see com.voxapps.location.AndroidLiveLocationProvider. Either counts as granted, matching how the app itself checks.
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> locationGranted = granted }

    var showPermissions by remember { mutableStateOf(false) }

    if (!showPermissions) {
        OnboardingWelcomeScreen(
            icon = Icons.Filled.Receipt,
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
            onContinue = { showPermissions = true }
        )
    } else {
        OnboardingPermissionsScreen(
            title = languageManager.getString("onboarding_permissions_title"),
            intro = languageManager.getString("onboarding_permissions_intro"),
            permissions = buildList {
                if (needsNotifPermission) {
                    add(
                        OnboardingPermission(
                            title = languageManager.getString("permission_notif_title"),
                            description = languageManager.getString("permission_notif_desc"),
                            granted = notifGranted,
                            onRequest = { requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        )
                    )
                }
                add(
                    OnboardingPermission(
                        title = languageManager.getString("permission_location_title"),
                        description = languageManager.getString("permission_location_desc"),
                        granted = locationGranted,
                        onRequest = { requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                    )
                )
            },
            grantedLabel = languageManager.getString("permission_status_granted"),
            requiredLabel = languageManager.getString("permission_status_required"),
            continueLabel = languageManager.getString("continue_button"),
            onContinue = { stateManager.setOnboardingCompleted(true) }
        )
    }
}
