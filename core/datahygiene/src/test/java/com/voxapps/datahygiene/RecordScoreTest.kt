package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private enum class TestProvenance(override val trustTier: Int) : RecordProvenance {
    HIGH(400), LOW(100)
}

class RecordScoreTest {

    @Test
    fun `higher trust tier outranks lower tier at equal completeness`() {
        val high = recordScore(manuallyEdited = false, provenance = TestProvenance.HIGH, completenessFields = listOf("a", null))
        val low = recordScore(manuallyEdited = false, provenance = TestProvenance.LOW, completenessFields = listOf("a", null))
        assertTrue(high > low)
    }

    @Test
    fun `more complete record outranks a sparser one at the same tier`() {
        val complete = recordScore(manuallyEdited = false, provenance = TestProvenance.LOW, completenessFields = listOf("a", "b", "c"))
        val sparse = recordScore(manuallyEdited = false, provenance = TestProvenance.LOW, completenessFields = listOf("a", null, null))
        assertTrue(complete > sparse)
    }

    @Test
    fun `manually edited always outranks source tier and completeness combined`() {
        val editedButSparse = recordScore(manuallyEdited = true, provenance = TestProvenance.LOW, completenessFields = listOf(null, null, null))
        val unEditedButFullHighTier = recordScore(manuallyEdited = false, provenance = TestProvenance.HIGH, completenessFields = listOf("a", "b", "c"))
        assertTrue(editedButSparse > unEditedButFullHighTier)
    }

    @Test
    fun `completeness only counts non-null fields`() {
        val score = recordScore(manuallyEdited = false, provenance = TestProvenance.LOW, completenessFields = listOf("a", null, "b", null))
        assertEquals(TestProvenance.LOW.trustTier + 2, score)
    }
}
