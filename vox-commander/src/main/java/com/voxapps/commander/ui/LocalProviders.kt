package com.voxapps.commander.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.voxapps.commander.domain.localization.LanguageManager

/**
 * Ambient providers for UI-layer singletons, so we don't prop-drill them through
 * every composable. Provided once at the root in MainActivity.setContent.
 */
val LocalLanguageManager = staticCompositionLocalOf<LanguageManager> {
    error("LocalLanguageManager not provided")
}
