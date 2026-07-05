package com.voxcommander.app.utils

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Shared, process-lifetime coroutine scopes for fire-and-forget work.
 *
 * Replaces ad-hoc `CoroutineScope(Dispatchers.X).launch { }` sites, which were
 * non-cancelable and — lacking a [CoroutineExceptionHandler] — could crash the
 * whole process on an uncaught exception. Each scope uses a [SupervisorJob] so a
 * failing child never cancels its siblings, and an exception handler that logs
 * and swallows, keeping the process alive.
 */
object AppScope {
    private const val TAG = "AppScope"

    private val handler = CoroutineExceptionHandler { _, e ->
        Logger.log("Uncaught coroutine exception: ${e.message}", TAG)
    }

    /** Background/IO work. */
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    /** Main-thread work (UI-affecting side effects). */
    val main = CoroutineScope(SupervisorJob() + Dispatchers.Main + handler)
}
