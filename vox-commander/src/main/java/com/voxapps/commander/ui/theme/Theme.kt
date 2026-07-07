package com.voxapps.commander.ui.theme

import androidx.compose.runtime.Composable
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme

/**
 * Commander's thin wrapper over the shared VoxApps theme engine ([VoxTheme] in `:core:design`).
 * The two inputs come from persisted settings (`themeDarkMode` / `themeColored`) collected in
 * MainActivity and VoiceOverlayManager and passed down — instant switch, no restart.
 */
@Composable
fun VoxCommanderTheme(
    darkMode: VoxDarkMode = VoxDarkMode.SYSTEM,
    colored: Boolean = false,
    content: @Composable () -> Unit
) {
    VoxTheme(darkMode = darkMode, colored = colored, content = content)
}
