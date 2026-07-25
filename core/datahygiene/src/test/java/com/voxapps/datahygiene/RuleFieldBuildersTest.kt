package com.voxapps.datahygiene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class BuilderDummyRecord(val name: String?, val amount: Double?, val timestamp: Long)

private val containmentFuzzyMatcher = FuzzyMatcher { a, b -> b.contains(a, ignoreCase = true) || a.contains(b, ignoreCase = true) }

class RuleFieldBuildersTest {

    @Test
    fun `stringField is case-insensitive exact when fuzzy is off`() {
        val field = stringField<BuilderDummyRecord>("name", "label", fuzzyMatchEnabled = false, fuzzyMatcher = containmentFuzzyMatcher) { it.name }
        val existing = BuilderDummyRecord(name = "Example Store", amount = null, timestamp = 0)

        assertTrue(field.matches(BuilderDummyRecord(name = "example store", amount = null, timestamp = 0), existing))
        assertFalse(field.matches(BuilderDummyRecord(name = "Payment to Example Store", amount = null, timestamp = 0), existing))
    }

    @Test
    fun `stringField uses the fuzzy matcher only when enabled`() {
        val field = stringField<BuilderDummyRecord>("name", "label", fuzzyMatchEnabled = true, fuzzyMatcher = containmentFuzzyMatcher) { it.name }
        val existing = BuilderDummyRecord(name = "Example Store", amount = null, timestamp = 0)

        assertTrue(field.matches(BuilderDummyRecord(name = "Payment to Example Store", amount = null, timestamp = 0), existing))
    }

    @Test
    fun `stringField never matches a blank or garbage-null value, even against itself`() {
        val field = stringField<BuilderDummyRecord>("name", "label", fuzzyMatchEnabled = true, fuzzyMatcher = containmentFuzzyMatcher) { it.name }

        assertFalse(field.matches(BuilderDummyRecord(name = null, amount = null, timestamp = 0), BuilderDummyRecord(name = "null", amount = null, timestamp = 0)))
    }

    @Test
    fun `stringField with FuzzyMatcher NONE ignores fuzzyMatchEnabled and stays exact`() {
        val field = stringField<BuilderDummyRecord>("name", "label", fuzzyMatchEnabled = true) { it.name }
        val existing = BuilderDummyRecord(name = "Example Store", amount = null, timestamp = 0)

        assertFalse(field.matches(BuilderDummyRecord(name = "Payment to Example Store", amount = null, timestamp = 0), existing))
    }

    @Test
    fun `exactField matches equal values and rejects a null on either side`() {
        val field = exactField<BuilderDummyRecord>("amount", "label") { it.amount }

        assertTrue(field.matches(BuilderDummyRecord(name = null, amount = 10.0, timestamp = 0), BuilderDummyRecord(name = null, amount = 10.0, timestamp = 0)))
        assertFalse(field.matches(BuilderDummyRecord(name = null, amount = null, timestamp = 0), BuilderDummyRecord(name = null, amount = null, timestamp = 0)))
        assertFalse(field.matches(BuilderDummyRecord(name = null, amount = 10.0, timestamp = 0), BuilderDummyRecord(name = null, amount = 11.0, timestamp = 0)))
    }

    @Test
    fun `timeWindowField matches within the window and not just outside it`() {
        val field = timeWindowField<BuilderDummyRecord>("timestamp", "label", windowMillis = 1000L) { it.timestamp }

        assertTrue(field.matches(BuilderDummyRecord(name = null, amount = null, timestamp = 1000L), BuilderDummyRecord(name = null, amount = null, timestamp = 0L)))
        assertFalse(field.matches(BuilderDummyRecord(name = null, amount = null, timestamp = 1001L), BuilderDummyRecord(name = null, amount = null, timestamp = 0L)))
    }
}
