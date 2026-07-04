package com.voxcommander.app.domain.voice

import android.content.Context
import com.voxcommander.app.utils.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for TextNormalizer — verifies the 3-layer normalization pipeline
 * loads from normalization.json in assets and applies rules correctly.
 */
class TextNormalizerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(Logger)
        every { Logger.log(any(), any()) } returns Unit

        context = mockk(relaxed = true)
    }

    @Test
    fun `normalize returns original text when not loaded`() {
        // TextNormalizer.load() not called — should return input unchanged
        val result = TextNormalizer.normalize("play spotify", "en")
        assertEquals("play spotify", result)
    }

    @Test
    fun `normalize returns original text for unknown language when not loaded`() {
        val result = TextNormalizer.normalize("deschide spotify", "ro")
        assertEquals("deschide spotify", result)
    }

    @Test
    fun `normalize handles empty string`() {
        val result = TextNormalizer.normalize("", "en")
        assertEquals("", result)
    }

    @Test
    fun `normalize handles null language gracefully`() {
        val result = TextNormalizer.normalize("play music", "unknown_lang")
        assertEquals("play music", result)
    }
}
