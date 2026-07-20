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
 *
 * [forcedViaDebugProperty] is a bypass for live on-device debugging without touching app settings
 * (or in a release build, where there's no debugger/run-as access): `adb shell setprop
 * debug.voxapps.forcelog true` then restart the app process (a system property is read once and
 * cached for the process's lifetime, so a running process won't pick it up until it restarts) —
 * every `Logger` call across every app sharing this module logs regardless of that app's own
 * persisted toggle. Unset with `adb shell setprop debug.voxapps.forcelog false` (or just reboot;
 * `debug.*` properties, unlike `persist.*` ones, don't survive a reboot anyway).
 */
object Logger {
    @Volatile private var enabled: Boolean = false
    @Volatile private var toastsEnabled: Boolean = false
    @Volatile private var appContext: Context? = null

    private val forcedViaDebugProperty: Boolean by lazy {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, "debug.voxapps.forcelog", "false") == "true"
        } catch (e: Exception) {
            false
        }
    }

    private fun active(): Boolean = enabled || forcedViaDebugProperty

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setToastsEnabled(value: Boolean, context: Context? = null) {
        toastsEnabled = value
        if (context != null) {
            appContext = context.applicationContext
        }
    }

    fun isEnabled(): Boolean = active()

    fun d(tag: String, message: String) {
        if (active()) {
            Log.d(tag, message)
            if (toastsEnabled) showToast(message)
        }
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (active()) {
            Log.w(tag, message, t)
            if (toastsEnabled) showToast("WARN: $message")
        }
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (active()) {
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
