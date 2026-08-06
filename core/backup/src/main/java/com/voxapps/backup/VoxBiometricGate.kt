package com.voxapps.backup

/** The caller's existing session-validity check (e.g. `sessionManager::isSessionValid`). */
fun interface VoxSessionValidityCheck {
    fun isValid(timeoutMinutes: Int): Boolean
}

/**
 * The `isBiometricRequired && !sessionManager.isSessionValid(...)` gate duplicated at the top of
 * every export()/import() in Expenses/Notes/Calendar's handlers — an export/import request while
 * the app is locked must never touch the DB.
 */
object VoxBiometricGate {
    fun isLocked(
        isBiometricRequired: Boolean,
        sessionTimeoutMinutes: Int,
        sessionValidityCheck: VoxSessionValidityCheck
    ): Boolean = isBiometricRequired && !sessionValidityCheck.isValid(sessionTimeoutMinutes)
}
