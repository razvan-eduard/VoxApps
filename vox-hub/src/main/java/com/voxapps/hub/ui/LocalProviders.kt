package com.voxapps.hub.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.voxapps.hub.domain.localization.LanguageManager

/**
 * Ambient provider for [LanguageManager] so it doesn't need to be prop-drilled through every
 * composable. Provided once at the root in HubActivity.setContent (mirrors vox-vision).
 */
val LocalLanguageManager = staticCompositionLocalOf<LanguageManager> {
    error("LocalLanguageManager not provided")
}
