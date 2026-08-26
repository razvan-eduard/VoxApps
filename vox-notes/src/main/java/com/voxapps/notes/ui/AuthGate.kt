package com.voxapps.notes.ui

import androidx.compose.runtime.Composable

/** The barrier itself lives in :core:applock; this keeps the app's labels and call sites as-is. */
@Composable
fun AuthGate(onUnlockRequest: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    com.voxapps.applock.AuthGate(
        title = languageManager.getString("locked_title"),
        buttonLabel = languageManager.getString("unlock"),
        onUnlockRequest = onUnlockRequest
    )
}
