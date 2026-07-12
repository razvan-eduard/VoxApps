package com.voxapps.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.hub.ui.HubScreen
import com.voxapps.hub.ui.LocalLanguageManager

/** Standalone launcher, mirrors vox-vision's VisionActivity shape. */
class HubActivity : ComponentActivity() {

    private val container by lazy { (application as HubApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
                VoxTheme(darkMode = VoxDarkMode.SYSTEM, colored = true) {
                    HubScreen()
                }
            }
        }
    }
}
