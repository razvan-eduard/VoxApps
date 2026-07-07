package com.voxapps.design

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** How the app resolves light vs dark. SYSTEM follows the OS setting. */
enum class VoxDarkMode { SYSTEM, LIGHT, DARK }

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * The shared VoxApps theme engine. Two independent inputs resolve to one of four schemes:
 * - [darkMode]: SYSTEM / LIGHT / DARK
 * - [colored]: false = static Vox brand palette; true = Material You dynamic color (Android 12+,
 *   static fallback below). Stateless — each app owns its own persistence and passes the values in.
 */
@Composable
fun VoxTheme(
    darkMode: VoxDarkMode = VoxDarkMode.SYSTEM,
    colored: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (darkMode) {
        VoxDarkMode.SYSTEM -> isSystemInDarkTheme()
        VoxDarkMode.LIGHT -> false
        VoxDarkMode.DARK -> true
    }
    val colorScheme = when {
        colored && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VoxTypography,
        content = content
    )
}
