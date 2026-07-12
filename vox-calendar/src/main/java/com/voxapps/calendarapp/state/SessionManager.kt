package com.voxapps.calendarapp.state

import com.voxapps.calendarapp.data.preferences.CalendarSettings

/**
 * In-memory biometric session (mirrors vox-expenses' SessionManager). Holds the last successful-unlock
 * timestamp; [isSessionValid] recomputes freshness against the configured timeout. This is a UI/access
 * gate, not the DB encryption key (that stays Keystore-backed).
 *
 * Kept process-local on purpose: a cold start starts locked (no persisted "unlocked" flag), which is
 * the safer default. [clock] is injectable for deterministic tests.
 */
class SessionManager(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile private var lastUnlockedTimestamp: Long = Long.MIN_VALUE

    fun markUnlocked() {
        lastUnlockedTimestamp = clock()
    }

    fun lock() {
        lastUnlockedTimestamp = Long.MIN_VALUE
    }

    /**
     * True if a prior unlock is still within [timeoutMinutes]. [CalendarSettings.TIMEOUT_UNLIMITED]
     * (-1) stays valid once unlocked; a never-unlocked session is always invalid.
     */
    fun isSessionValid(timeoutMinutes: Int): Boolean {
        if (lastUnlockedTimestamp == Long.MIN_VALUE) return false
        if (timeoutMinutes == CalendarSettings.TIMEOUT_UNLIMITED) return true
        val elapsed = clock() - lastUnlockedTimestamp
        return elapsed < timeoutMinutes * 60_000L
    }
}
