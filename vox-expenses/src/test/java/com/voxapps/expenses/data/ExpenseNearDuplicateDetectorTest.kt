package com.voxapps.expenses.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExpenseNearDuplicateDetectorTest {

    private val windowMillis = TimeUnit.MINUTES.toMillis(2)

    private fun expense(
        title: String? = "Example Store",
        vendor: String? = null,
        totalAmount: Double = 42.0,
        currencyCode: String = "RON",
        direction: TransactionDirection = TransactionDirection.OUTGOING,
        dateTime: Long = 1_000_000L
    ) = Expense(title = title, vendor = vendor, totalAmount = totalAmount, currencyCode = currencyCode, direction = direction, dateTime = dateTime)

    @Test
    fun `exact mode requires case-insensitive equality, not just containment`() {
        val detector = ExpenseNearDuplicateDetector(fuzzyMatchEnabled = false, timeWindowMillis = windowMillis)
        val existing = expense(title = "Example Store")
        val exactMatch = expense(title = "example store")
        val containmentOnly = expense(title = "Payment to Example Store")

        assertTrue(detector.isDuplicateOf(exactMatch, existing))
        assertFalse(detector.isDuplicateOf(containmentOnly, existing))
    }

    @Test
    fun `fuzzy mode accepts a containment match`() {
        val detector = ExpenseNearDuplicateDetector(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val existing = expense(title = "Example Store")
        val candidate = expense(title = "Payment to Example Store")

        assertTrue(detector.isDuplicateOf(candidate, existing))
    }

    @Test
    fun `mismatched amount never matches regardless of name`() {
        val detector = ExpenseNearDuplicateDetector(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val existing = expense(totalAmount = 42.0)
        val candidate = expense(totalAmount = 43.0)

        assertFalse(detector.isDuplicateOf(candidate, existing))
    }

    @Test
    fun `mismatched currency never matches`() {
        val detector = ExpenseNearDuplicateDetector(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val existing = expense(currencyCode = "RON")
        val candidate = expense(currencyCode = "EUR")

        assertFalse(detector.isDuplicateOf(candidate, existing))
    }

    @Test
    fun `mismatched direction never matches`() {
        val detector = ExpenseNearDuplicateDetector(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val existing = expense(direction = TransactionDirection.OUTGOING)
        val candidate = expense(direction = TransactionDirection.INCOMING)

        assertFalse(detector.isDuplicateOf(candidate, existing))
    }

    @Test
    fun `a dateTime delta within the window matches, just outside it does not`() {
        val detector = ExpenseNearDuplicateDetector(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val existing = expense(dateTime = 1_000_000L)
        val withinWindow = expense(dateTime = 1_000_000L + windowMillis)
        val outsideWindow = expense(dateTime = 1_000_000L + windowMillis + 1)

        assertTrue(detector.isDuplicateOf(withinWindow, existing))
        assertFalse(detector.isDuplicateOf(outsideWindow, existing))
    }

    @Test
    fun `falls back to vendor when title is blank on either side`() {
        val detector = ExpenseNearDuplicateDetector(fuzzyMatchEnabled = false, timeWindowMillis = windowMillis)
        val existing = expense(title = null, vendor = "Example Store")
        val candidate = expense(title = null, vendor = "Example Store")

        assertTrue(detector.isDuplicateOf(candidate, existing))
    }

    @Test
    fun `never matches when neither side has a usable title or vendor`() {
        val detector = ExpenseNearDuplicateDetector(fuzzyMatchEnabled = true, timeWindowMillis = windowMillis)
        val existing = expense(title = null, vendor = null)
        val candidate = expense(title = null, vendor = null)

        assertFalse(detector.isDuplicateOf(candidate, existing))
    }

    @Test
    fun `enrichWithNearDuplicate fills blank fields without overwriting populated ones`() {
        val existing = expense().copy(bank = "Existing Bank", location = null, categoryId = null)
        val candidate = expense().copy(bank = "Other Bank", location = "Bucharest", categoryId = 7L)

        val merged = enrichWithNearDuplicate(existing, candidate)

        assertEquals("Existing Bank", merged.bank)
        assertEquals("Bucharest", merged.location)
        assertEquals(7L, merged.categoryId)
    }

    @Test
    fun `enrichWithNearDuplicate leaves updatedAt unchanged when nothing merges`() {
        val existing = expense().copy(bank = "Existing Bank", location = "Bucharest", categoryId = 7L, updatedAt = 500L)
        val candidate = expense().copy(bank = "Other Bank", location = "Cluj", categoryId = 3L)

        val merged = enrichWithNearDuplicate(existing, candidate)

        assertEquals(500L, merged.updatedAt)
    }

    @Test
    fun `enrichWithNearDuplicate bumps updatedAt when a field actually merges`() {
        val existing = expense().copy(bank = null, updatedAt = 500L)
        val candidate = expense().copy(bank = "Other Bank")

        val merged = enrichWithNearDuplicate(existing, candidate)

        assertTrue(merged.updatedAt > 500L)
    }
}
