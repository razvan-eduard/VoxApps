package com.voxapps.design.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelVersioningTest {

    @Test
    fun `older versioned channels are stale`() {
        val existing = listOf("entry_reminders_v1", "entry_reminders_v2", "entry_reminders_v3")
        val stale = NotificationChannelVersioning.staleChannelIds(existing, "entry_reminders", "entry_reminders_v3")
        assertEquals(listOf("entry_reminders_v1", "entry_reminders_v2"), stale)
    }

    @Test
    fun `unversioned base channel is stale once a versioned one becomes active`() {
        val existing = listOf("entry_reminders", "entry_reminders_v1")
        val stale = NotificationChannelVersioning.staleChannelIds(existing, "entry_reminders", "entry_reminders_v1")
        assertEquals(listOf("entry_reminders"), stale)
    }

    @Test
    fun `versioned channel is stale once switched back to the unversioned base`() {
        val existing = listOf("entry_reminders_v4", "entry_reminders")
        val stale = NotificationChannelVersioning.staleChannelIds(existing, "entry_reminders", "entry_reminders")
        assertEquals(listOf("entry_reminders_v4"), stale)
    }

    @Test
    fun `channels from a different base id are never touched`() {
        val existing = listOf("spending_limit_alerts_v2", "entry_reminders_v1")
        val stale = NotificationChannelVersioning.staleChannelIds(existing, "entry_reminders", "entry_reminders_v1")
        assertTrue(stale.none { it.startsWith("spending_limit_alerts") })
    }

    @Test
    fun `the active channel is never reported as stale`() {
        val existing = listOf("entry_reminders_v1")
        val stale = NotificationChannelVersioning.staleChannelIds(existing, "entry_reminders", "entry_reminders_v1")
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `no existing channels means nothing to delete`() {
        val stale = NotificationChannelVersioning.staleChannelIds(emptyList(), "entry_reminders", "entry_reminders_v1")
        assertTrue(stale.isEmpty())
    }
}
