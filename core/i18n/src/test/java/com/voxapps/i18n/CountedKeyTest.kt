package com.voxapps.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class CountedKeyTest {

    @Test
    fun `one takes the _one form in every language`() {
        assertEquals("records_one", LanguageManager.countedKey("records", 1, usesLargeNumberForm = false))
        assertEquals("records_one", LanguageManager.countedKey("records", 1, usesLargeNumberForm = true))
    }

    @Test
    fun `small counts take the base form`() {
        assertEquals("records", LanguageManager.countedKey("records", 5, usesLargeNumberForm = false))
        assertEquals("records", LanguageManager.countedKey("records", 19, usesLargeNumberForm = true))
    }

    @Test
    fun `romanian zero and twenty-plus take the _many form`() {
        assertEquals("records_many", LanguageManager.countedKey("records", 0, usesLargeNumberForm = true))
        assertEquals("records_many", LanguageManager.countedKey("records", 20, usesLargeNumberForm = true))
        assertEquals("records_many", LanguageManager.countedKey("records", 137, usesLargeNumberForm = true))
    }

    @Test
    fun `languages without a large-number form never see _many`() {
        assertEquals("records", LanguageManager.countedKey("records", 0, usesLargeNumberForm = false))
        assertEquals("records", LanguageManager.countedKey("records", 20, usesLargeNumberForm = false))
    }
}
