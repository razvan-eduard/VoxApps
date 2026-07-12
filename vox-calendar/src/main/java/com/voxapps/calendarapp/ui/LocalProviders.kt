package com.voxapps.calendarapp.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.voxapps.calendarapp.domain.localization.LanguageManager

/**
 * Ambient provider for [LanguageManager] so it doesn't need to be prop-drilled through every
 * composable. Provided once at the root in CalendarActivity.setContent (mirrors vox-expenses).
 */
val LocalLanguageManager = staticCompositionLocalOf<LanguageManager> {
    error("LocalLanguageManager not provided")
}
