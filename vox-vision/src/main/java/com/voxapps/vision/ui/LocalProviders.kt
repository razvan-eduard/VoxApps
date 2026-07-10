package com.voxapps.vision.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.voxapps.vision.domain.localization.LanguageManager

/**
 * Ambient provider for [LanguageManager] so it doesn't need to be prop-drilled through every
 * composable. Provided once at the root in VisionActivity.setContent (mirrors vox-notes).
 */
val LocalLanguageManager = staticCompositionLocalOf<LanguageManager> {
    error("LocalLanguageManager not provided")
}
