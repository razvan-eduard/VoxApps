package com.voxapps.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxBiometricGateTest {

    @Test
    fun `not locked when biometric not required, regardless of session validity`() {
        assertFalse(VoxBiometricGate.isLocked(isBiometricRequired = false, sessionTimeoutMinutes = 5) { false })
    }

    @Test
    fun `not locked when biometric required but session still valid`() {
        assertFalse(VoxBiometricGate.isLocked(isBiometricRequired = true, sessionTimeoutMinutes = 5) { true })
    }

    @Test
    fun `locked when biometric required and session invalid`() {
        assertTrue(VoxBiometricGate.isLocked(isBiometricRequired = true, sessionTimeoutMinutes = 5) { false })
    }

    @Test
    fun `passes the timeout value through to the validity check`() {
        var receivedTimeout: Int? = null
        VoxBiometricGate.isLocked(isBiometricRequired = true, sessionTimeoutMinutes = 42) {
            receivedTimeout = it
            true
        }
        assertEquals(42, receivedTimeout)
    }
}
