package com.voxapps.expenses.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.voxapps.expenses.domain.localization.LanguageManager

/**
 * Ambient provider for [LanguageManager] so it doesn't need to be prop-drilled through every
 * composable. Provided once at the root in ExpensesActivity.setContent (mirrors vox-notes).
 */
val LocalLanguageManager = staticCompositionLocalOf<LanguageManager> {
    error("LocalLanguageManager not provided")
}
