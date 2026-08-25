package com.voxapps.expenses.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ArchiveRetentionTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a retention of days is a cutoff that many days back`() {
        assertEquals(now - TimeUnit.DAYS.toMillis(30), ArchiveRetention.cutoff(30, now))
        assertEquals(now - TimeUnit.DAYS.toMillis(365), ArchiveRetention.cutoff(365, now))
    }

    /** The one that matters: keeping everything has to be the absence of a cutoff, not a cutoff of
     *  now — which would delete the entire archive on the next pass. */
    @Test
    fun `keeping everything is no cutoff at all`() {
        assertNull(ArchiveRetention.cutoff(0, now))
        assertNull(ArchiveRetention.cutoff(-1, now))
    }
}
