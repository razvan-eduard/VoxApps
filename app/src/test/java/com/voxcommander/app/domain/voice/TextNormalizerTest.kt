package com.voxcommander.app.domain.voice

import android.content.Context
import android.content.res.AssetManager
import com.voxcommander.app.utils.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Tests for TextNormalizer — verifies the 3-layer normalization pipeline
 * loads from normalization.json in assets and applies rules correctly.
 *
 * TextNormalizer is a stateful `object`, so each test resets it to the
 * not-loaded state via [resetNormalizer] in @After.
 */
class TextNormalizerTest {

    private lateinit var context: Context

    // Controlled fixture (deterministic; independent of the production normalization.json).
    // en exercises all 3 layers; ro has layer 1 only (to prove per-language isolation).
    private val fixture = """
        {
          "schema_version": 1,
          "en": {
            "layer_1_replacements": { "rules": { "\\bwi fi\\b": "wifi" } },
            "layer_2_regex": { "rules": [ { "pattern": "(?i)\\bplease\\b", "replacement": "" } ] },
            "layer_3_cleanup": { "rules": { "\\s+": " " } }
          },
          "ro": {
            "layer_1_replacements": { "rules": { "\\bshpotifai\\b": "spotify" } }
          }
        }
    """.trimIndent()

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(Logger)
        every { Logger.log(any(), any()) } returns Unit

        context = mockk(relaxed = true)
        resetNormalizer()
    }

    @After
    fun tearDown() {
        resetNormalizer()
    }

    /** Loads [json] as the normalization asset. */
    private fun loadFixture(json: String) {
        val assets = mockk<AssetManager>()
        every { assets.open("normalization.json") } answers { ByteArrayInputStream(json.toByteArray()) }
        every { context.assets } returns assets
        TextNormalizer.reload(context)
    }

    /** Returns TextNormalizer to the not-loaded state (reload clears maps; failing open() leaves loaded=false). */
    private fun resetNormalizer() {
        val throwingCtx = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { throwingCtx.assets } returns assets
        every { assets.open(any()) } throws IOException("reset")
        TextNormalizer.reload(throwingCtx)
    }

    // --- Not-loaded passthrough ---

    @Test
    fun `normalize returns original text when not loaded`() {
        assertEquals("play spotify", TextNormalizer.normalize("play spotify", "en"))
    }

    @Test
    fun `normalize returns original text for unknown language when not loaded`() {
        assertEquals("deschide spotify", TextNormalizer.normalize("deschide spotify", "ro"))
    }

    @Test
    fun `normalize handles empty string`() {
        assertEquals("", TextNormalizer.normalize("", "en"))
    }

    @Test
    fun `normalize handles null language gracefully`() {
        assertEquals("play music", TextNormalizer.normalize("play music", "unknown_lang"))
    }

    // --- Loaded rules ---

    @Test
    fun `applies all three layers in order`() {
        loadFixture(fixture)
        // L1: "wi fi"->"wifi"; L2: remove "please"; L3: collapse spaces + trim.
        assertEquals("turn on the wifi", TextNormalizer.normalize("turn on the wi fi please", "en"))
    }

    @Test
    fun `layer 3 collapses whitespace and trims`() {
        loadFixture(fixture)
        assertEquals("a b", TextNormalizer.normalize("  a    b  ", "en"))
    }

    @Test
    fun `locale suffix is stripped to base language`() {
        loadFixture(fixture)
        assertEquals("wifi", TextNormalizer.normalize("wi fi", "en_US"))
    }

    @Test
    fun `unsupported language falls back to english pipeline`() {
        loadFixture(fixture)
        assertEquals("wifi", TextNormalizer.normalize("wi fi", "de"))
    }

    @Test
    fun `each language uses only its own rules`() {
        loadFixture(fixture)
        // ro applies its layer-1 rule...
        assertEquals("pune spotify", TextNormalizer.normalize("pune shpotifai", "ro"))
        // ...but not the en "wi fi" rule.
        assertEquals("wi fi", TextNormalizer.normalize("wi fi", "ro"))
    }

    @Test
    fun `text with no matching rule passes through unchanged`() {
        loadFixture(fixture)
        assertEquals("hello world", TextNormalizer.normalize("hello world", "en"))
    }
}
