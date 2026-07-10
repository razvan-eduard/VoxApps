package com.voxapps.notes.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.voxapps.notes.domain.localization.LanguageManager

/**
 * Ambient provider for [LanguageManager] so it doesn't need to be prop-drilled through every
 * composable. Provided once at the root in NotesActivity.setContent (mirrors vox-commander).
 */
val LocalLanguageManager = staticCompositionLocalOf<LanguageManager> {
    error("LocalLanguageManager not provided")
}
