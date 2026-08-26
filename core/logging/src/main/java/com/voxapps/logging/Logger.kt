package com.voxapps.logging

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Legacy 4-state shorthand for [LoggingFlags], kept for callers (Commander) that persist it as a
 *  single string setting. */
enum class LogLevel { NONE, TOAST_ONLY, LOGCAT_ONLY, TOAST_AND_LOGCAT }

data class LoggingFlags(
    val toastEnabled: Boolean = false,
    val logcatEnabled: Boolean = false
) {
    companion object {
        fun fromLogLevel(level: LogLevel): LoggingFlags = when (level) {
            LogLevel.NONE -> LoggingFlags(toastEnabled = false, logcatEnabled = false)
            LogLevel.TOAST_ONLY -> LoggingFlags(toastEnabled = true, logcatEnabled = false)
            LogLevel.LOGCAT_ONLY -> LoggingFlags(toastEnabled = false, logcatEnabled = true)
            LogLevel.TOAST_AND_LOGCAT -> LoggingFlags(toastEnabled = true, logcatEnabled = true)
        }

        fun toLogLevel(flags: LoggingFlags): LogLevel = when {
            flags.toastEnabled && flags.logcatEnabled -> LogLevel.TOAST_AND_LOGCAT
            flags.toastEnabled -> LogLevel.TOAST_ONLY
            flags.logcatEnabled -> LogLevel.LOGCAT_ONLY
            else -> LogLevel.NONE
        }
    }
}

/**
 * Shared logger for every Vox app: gated logcat/toast output plus an in-memory ring buffer (capped
 * at [MAX_LOG_ENTRIES]) that an in-app viewer can render (`ui/LogViewerCard.kt`,
 * `ui/LogsSettingsTab.kt`). Originally Commander-only — the satellites had a much thinner
 * logcat-only wrapper with no viewer — this module is now that richer version, adopted by all 5
 * apps so logging behaves and looks the same everywhere.
 *
 * Toast, logcat, and ring-buffer capture are three independent gates (mirrors Commander's original
 * [LoggingFlags]/verbose-logging split): a call can produce a toast without touching logcat, etc.
 * [setEnabled] is the simple on/off satellites use for their single "debug logging" toggle — it
 * flips logcat and the ring buffer together; Commander instead drives [setLoggingFlags] and
 * [setVerboseLoggingEnabled] independently from its own richer settings tab.
 *
 * Each app calls [initialize] once at startup with its own name. Every logcat line this Logger
 * writes uses that name as the literal Android log tag (the caller-supplied `tag` becomes a
 * `[tag]` prefix inside the message instead), so `adb logcat -s VoxNotes` (etc.) isolates exactly
 * that app's output even while several Vox apps are running at once.
 *
 * [forcedViaDebugProperty] is a bypass for live/release-build debugging without touching app
 * settings: `adb shell setprop debug.voxapps.forcelog true` then restart the app process (a system
 * property is read once and cached for the process's lifetime) forces logcat output regardless of
 * the persisted toggle. Unset with `adb shell setprop debug.voxapps.forcelog false` (or reboot;
 * `debug.*` properties don't survive one anyway).
 */
object Logger {
    private const val MAX_LOG_ENTRIES = 100
    private const val DEFAULT_TAG = "Vox"

    data class LogEntry(val message: String, val tag: String, val timestamp: Long)

    @Volatile private var appContext: Context? = null
    @Volatile private var appName: String = "VoxApps"
    @Volatile private var loggingFlags: LoggingFlags = LoggingFlags()
    @Volatile private var verboseLoggingEnabled: Boolean = false

    private val _verboseLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val verboseLogs: StateFlow<List<LogEntry>> = _verboseLogs

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val forcedViaDebugProperty: Boolean by lazy {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, "debug.voxapps.forcelog", "false") == "true"
        } catch (e: Exception) {
            false
        }
    }

    private fun active(): Boolean = loggingFlags.logcatEnabled || forcedViaDebugProperty

    fun initialize(context: Context, appName: String, level: LogLevel = LogLevel.LOGCAT_ONLY) {
        appContext = context.applicationContext
        this.appName = appName
        setLogLevel(level)
    }

    fun setLogLevel(level: LogLevel) {
        loggingFlags = LoggingFlags.fromLogLevel(level)
    }

    fun setLoggingFlags(flags: LoggingFlags) {
        loggingFlags = flags
    }

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        verboseLoggingEnabled = enabled
        if (!enabled) _verboseLogs.value = emptyList()
    }

    fun setEnabled(value: Boolean) {
        loggingFlags = loggingFlags.copy(logcatEnabled = value)
        setVerboseLoggingEnabled(value)
    }

    fun setToastsEnabled(value: Boolean, context: Context? = null) {
        loggingFlags = loggingFlags.copy(toastEnabled = value)
        if (context != null) appContext = context.applicationContext
    }

    fun isEnabled(): Boolean = active()

    fun clearVerboseLogs() {
        _verboseLogs.value = emptyList()
    }

    fun d(tag: String, message: String) = emit(Log.DEBUG, tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) = emit(Log.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = emit(Log.ERROR, tag, message, t)

    /** Back-compat with Commander's original call shape (`Logger.log(message, tag)`, ~630 call
     *  sites) so migrating it onto this shared module was a one-line-per-file import swap. */
    fun log(message: String, tag: String = DEFAULT_TAG) = emit(Log.DEBUG, tag, message)

    private fun emit(priority: Int, tag: String, message: String, t: Throwable? = null) {
        if (active()) {
            val fullMessage = "[$tag] $message"
            when (priority) {
                Log.WARN -> Log.w(appName, fullMessage, t)
                Log.ERROR -> Log.e(appName, fullMessage, t)
                else -> Log.d(appName, fullMessage)
            }
        }
        if (loggingFlags.toastEnabled) {
            val prefix = when (priority) {
                Log.ERROR -> "ERROR: "
                Log.WARN -> "WARN: "
                else -> ""
            }
            showToast(prefix + message)
        }
        if (verboseLoggingEnabled) addEntry(tag, message)
    }

    private fun addEntry(tag: String, message: String) {
        // update {} retries on contention — two threads logging at once must not overwrite each
        // other's entry the way separate read-modify-write on .value did.
        _verboseLogs.update { logs ->
            (listOf(LogEntry(message, tag, System.currentTimeMillis())) + logs).take(MAX_LOG_ENTRIES)
        }
    }

    private fun showToast(message: String) {
        val ctx = appContext ?: return
        mainHandler.post {
            try {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Ignore toast failures (e.g. no foreground activity)
            }
        }
    }
}
