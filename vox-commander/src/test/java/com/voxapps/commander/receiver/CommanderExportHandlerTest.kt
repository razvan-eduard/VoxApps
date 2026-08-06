package com.voxapps.commander.receiver

import com.voxapps.commander.testutil.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommanderExportHandlerTest {

    @Test
    fun `buildFastMapRulesJson then parseFastMapRules round-trips every field`() {
        val rules = listOf(
            TestDataFactory.createFastMapRule(
                id = 1L,
                allWords = listOf("turn", "on", "the", "flashlight"),
                triggerWords = listOf("flashlight"),
                queryWords = emptyList(),
                domain = "settings",
                action = "flashlight_on"
            ),
            TestDataFactory.createFastMapRule(
                id = 2L,
                targetPackage = "com.example.app",
                intentAction = "android.intent.action.VIEW",
                anyOrder = true
            )
        )

        val json = CommanderExportHandler.buildFastMapRulesJson(rules)
        val parsed = CommanderExportHandler.parseFastMapRules(json)

        assertEquals(rules, parsed)
    }

    @Test
    fun `parseFastMapRules returns null for invalid json`() {
        assertNull(CommanderExportHandler.parseFastMapRules("{ not json"))
    }

    @Test
    fun `parseFastMapRules returns empty list for an empty array`() {
        val parsed = CommanderExportHandler.parseFastMapRules("[]")
        assertTrue(parsed != null && parsed.isEmpty())
    }
}
