package com.voxapps.hub.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupConfigTest {

    @Test
    fun `an app missing from the map falls back to DEFAULT`() {
        val config = emptyMap<String, AppBackupConfig>().configFor("com.voxapps.notes")
        assertEquals(AppBackupConfig.DEFAULT, config)
        assertTrue(config.includeSettings)
        assertTrue(config.includeData)
        assertFalse(config.includeApiKeys)
        assertFalse(config.includeAttachments)
    }

    @Test
    fun `encodeMap then decodeMap round-trips every field for every package`() {
        val original = mapOf(
            "com.voxapps.notes" to AppBackupConfig(includeSettings = true, includeData = false, includeApiKeys = false, includeAttachments = true),
            "com.voxapps.expenses" to AppBackupConfig(includeSettings = false, includeData = true, includeApiKeys = true, includeAttachments = false)
        )

        val decoded = AppBackupConfig.decodeMap(AppBackupConfig.encodeMap(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `decodeMap on malformed json yields an empty map, not a crash`() {
        assertEquals(emptyMap<String, AppBackupConfig>(), AppBackupConfig.decodeMap("not json"))
        assertEquals(emptyMap<String, AppBackupConfig>(), AppBackupConfig.decodeMap(""))
    }

    @Test
    fun `fromJson defaults missing fields the same way DEFAULT does`() {
        val config = AppBackupConfig.fromJson(org.json.JSONObject("{}"))
        assertEquals(AppBackupConfig.DEFAULT, config)
    }

    @Test
    fun `wantsExport is true unless both settings and data are off`() {
        assertTrue(AppBackupConfig(includeSettings = true, includeData = false).wantsExport())
        assertTrue(AppBackupConfig(includeSettings = false, includeData = true).wantsExport())
        assertTrue(AppBackupConfig(includeSettings = true, includeData = true).wantsExport())
        assertFalse(AppBackupConfig(includeSettings = false, includeData = false).wantsExport())
    }
}
