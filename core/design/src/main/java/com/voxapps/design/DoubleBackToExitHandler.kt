package com.voxapps.design

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val EXIT_WINDOW_MS = 2000L

/**
 * Standard "press back again to exit" pattern for an app's main/home screen, shared across the Vox
 * satellite apps so the timestamp+Toast bookkeeping isn't duplicated per app. Finishes the current
 * Activity on a second back-press within [EXIT_WINDOW_MS] of the first; otherwise shows [message].
 */
@Composable
fun DoubleBackToExitHandler(message: String, enabled: Boolean = true) {
    val context = LocalContext.current
    var lastBackPressAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = enabled) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt < EXIT_WINDOW_MS) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressAt = now
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
