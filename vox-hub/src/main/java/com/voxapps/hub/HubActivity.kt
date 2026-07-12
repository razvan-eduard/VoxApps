package com.voxapps.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.hub.data.preferences.HubSettings
import com.voxapps.hub.ui.HubScreen
import com.voxapps.hub.ui.HubSettingsScreen
import com.voxapps.hub.ui.LocalLanguageManager

/** Standalone launcher, mirrors vox-vision's VisionActivity shape. */
class HubActivity : ComponentActivity() {

    private val container by lazy { (application as HubApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by container.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
                initialValue = HubSettings()
            )
            CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
                VoxTheme(
                    darkMode = when (settings.themeDarkMode) {
                        HubSettings.THEME_LIGHT -> VoxDarkMode.LIGHT
                        HubSettings.THEME_DARK -> VoxDarkMode.DARK
                        else -> VoxDarkMode.SYSTEM
                    },
                    colored = settings.themeColored
                ) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        HubSettingsScreen(
                            settingsRepo = container.settingsRepository,
                            onBack = { showSettings = false }
                        )
                    } else {
                        HubScreen(onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}
