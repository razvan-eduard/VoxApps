package com.voxapps.commander.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TtsEngineType.PIPER]'s key has to equal the models.json engine key, because the TTS picker
 * persists whatever `getEngineKeysByType("tts")` handed it. When the two disagreed, `fromKey`
 * returned null for every stored value and TtsManager fell back to Android — i.e. picking Piper
 * did nothing at all.
 */
class TtsEngineTypeTest {

    @Test
    fun `the stored registry key resolves to Piper`() {
        assertEquals(TtsEngineType.PIPER, TtsEngineType.fromKey("piper_tts"))
    }

    /** Settings written before the key was corrected, and backups exported from those builds. */
    @Test
    fun `the legacy short spelling still resolves`() {
        assertEquals(TtsEngineType.PIPER, TtsEngineType.fromKey("piper"))
    }

    @Test
    fun `android is unchanged`() {
        assertEquals(TtsEngineType.ANDROID, TtsEngineType.fromKey("android"))
    }

    /** TtsManager treats null as "fall back to Android", so unknown input must not resolve to
     *  Piper by accident. */
    @Test
    fun `unknown and null do not resolve`() {
        assertNull(TtsEngineType.fromKey("garbage"))
        assertNull(TtsEngineType.fromKey(null))
        assertNull(TtsEngineType.fromKey(""))
    }

    /** The key is what gets persisted and what indexes the registry, so a rename is a data
     *  migration, not a refactor. */
    @Test
    fun `keys match the models_json engine keys`() {
        assertEquals("piper_tts", TtsEngineType.PIPER.key)
        assertEquals("android", TtsEngineType.ANDROID.key)
    }
}
