package com.voxapps.logging

import android.util.Log

/**
 * Shared, gated logcat wrapper for `vox-notes` and `vox-vision` (`vox-commander` keeps its own
 * richer `Logger` — Toast feedback + an in-app verbose-log viewer — since it runs mostly as a
 * background service where logcat alone isn't enough). [enabled] defaults to `false` so a normal
 * install never floods logcat; each app persists its own on/off flag (a Settings toggle) and calls
 * [setEnabled] at startup and on every change, so logging only happens when explicitly turned on.
 */
object Logger {
    @Volatile private var enabled: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (enabled) Log.w(tag, message, t)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (enabled) Log.e(tag, message, t)
    }
}
