package com.voxcommander.app.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logging levels for the application
 */
enum class LogLevel {
    NONE,
    TOAST_ONLY,
    LOGCAT_ONLY,
    TOAST_AND_LOGCAT
}

/**
 * Logging flags for checkbox-based logging
 */
data class LoggingFlags(
    val toastEnabled: Boolean = false,
    val logcatEnabled: Boolean = false
) {
    companion object {
        fun fromLogLevel(level: LogLevel): LoggingFlags {
            return when (level) {
                LogLevel.NONE -> LoggingFlags(toastEnabled = false, logcatEnabled = false)
                LogLevel.TOAST_ONLY -> LoggingFlags(toastEnabled = true, logcatEnabled = false)
                LogLevel.LOGCAT_ONLY -> LoggingFlags(toastEnabled = false, logcatEnabled = true)
                LogLevel.TOAST_AND_LOGCAT -> LoggingFlags(toastEnabled = true, logcatEnabled = true)
            }
        }

        fun toLogLevel(flags: LoggingFlags): LogLevel {
            return when {
                flags.toastEnabled && flags.logcatEnabled -> LogLevel.TOAST_AND_LOGCAT
                flags.toastEnabled && !flags.logcatEnabled -> LogLevel.TOAST_ONLY
                !flags.toastEnabled && flags.logcatEnabled -> LogLevel.LOGCAT_ONLY
                else -> LogLevel.NONE
            }
        }
    }
}

/**
 * Singleton logger that handles toast messages, logcat, and verbose logging
 */
object Logger {
    private const val VERBOSE_LOG_TAG = "VoxCommanderVerbose"

    private var context: Context? = null
    private var logLevel: LogLevel = LogLevel.TOAST_AND_LOGCAT
    private var loggingFlags: LoggingFlags = LoggingFlags(toastEnabled = true, logcatEnabled = true)
    private var verboseLoggingEnabled: Boolean = false
    
    // Flow for verbose log messages
    private val _verboseLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val verboseLogs: StateFlow<List<LogEntry>> = _verboseLogs
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    /**
     * Initialize the logger with context and logging level
     */
    fun initialize(context: Context, level: LogLevel = LogLevel.TOAST_AND_LOGCAT) {
        this.context = context
        this.logLevel = level
    }
    
    /**
     * Set the logging level
     */
    fun setLogLevel(level: LogLevel) {
        this.logLevel = level
        this.loggingFlags = LoggingFlags.fromLogLevel(level)
    }

    /**
     * Set logging flags directly from checkboxes
     */
    fun setLoggingFlags(flags: LoggingFlags) {
        this.loggingFlags = flags
        this.logLevel = LoggingFlags.toLogLevel(flags)
    }
    
    /**
     * Enable or disable verbose logging
     */
    fun setVerboseLoggingEnabled(enabled: Boolean) {
        this.verboseLoggingEnabled = enabled
        if (!enabled) {
            _verboseLogs.value = emptyList()
        }
    }
    
    /**
     * Log a message with the current logging level settings
     */
    fun log(message: String, tag: String = VERBOSE_LOG_TAG) {
        if (loggingFlags.toastEnabled) {
            showToast(message)
        }
        if (loggingFlags.logcatEnabled) {
            logToLogcat(message, tag)
        }

        // Always add to verbose logs if enabled
        if (verboseLoggingEnabled) {
            addVerboseLog(message, tag)
        }
    }
    
    /**
     * Show a toast message
     */
    private fun showToast(message: String) {
        context?.let { ctx ->
            coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Log to logcat
     */
    private fun logToLogcat(message: String, tag: String) {
        Log.d(tag, message)
    }
    
    /**
     * Add a log entry to verbose logs
     */
    private fun addVerboseLog(message: String, tag: String) {
        val entry = LogEntry(
            message = message,
            tag = tag,
            timestamp = System.currentTimeMillis()
        )
        val currentLogs = _verboseLogs.value.toMutableList()
        currentLogs.add(0, entry) // Add to beginning
        // Keep only last 100 logs
        if (currentLogs.size > 100) {
            currentLogs.removeAt(currentLogs.size - 1)
        }
        _verboseLogs.value = currentLogs
    }
    
    /**
     * Clear all verbose logs
     */
    fun clearVerboseLogs() {
        _verboseLogs.value = emptyList()
    }
    
    /**
     * Data class for log entries
     */
    data class LogEntry(
        val message: String,
        val tag: String,
        val timestamp: Long
    )
}
