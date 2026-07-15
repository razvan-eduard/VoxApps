package com.voxapps.logging

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Shared, gated logcat wrapper for `vox-notes` and `vox-vision` (`vox-commander` keeps its own
 * richer `Logger` — Toast feedback + an in-app verbose-log viewer — since it runs mostly as a
 * background service where logcat alone isn't enough). [enabled] defaults to `false` so a normal
 * install never floods logcat; each app persists its own on/off flag (a Settings toggle) and calls
 * [setEnabled] at startup and on every change, so logging only happens when explicitly turned on.
 */
object Logger {
    @Volatile private var enabled: Boolean = false
    @Volatile private var toastsEnabled: Boolean = false
    @Volatile private var appContext: Context? = null

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setToastsEnabled(value: Boolean, context: Context? = null) {
        toastsEnabled = value
        if (context != null) {
            appContext = context.applicationContext
        }
    }

    fun isEnabled(): Boolean = enabled

    fun d(tag: String, message: String) {
        if (enabled) {
            Log.d(tag, message)
            if (toastsEnabled) showToast(message)
        }
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (enabled) {
            Log.w(tag, message, t)
            if (toastsEnabled) showToast("WARN: $message")
        }
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (enabled) {
            Log.e(tag, message, t)
            if (toastsEnabled) showToast("ERROR: $message")
        }
    }

    private fun showToast(message: String) {
        appContext?.let {
            try {
                Toast.makeText(it, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Ignore toast failures (e.g. from background threads without Looper)
            }
        }
    }
}
