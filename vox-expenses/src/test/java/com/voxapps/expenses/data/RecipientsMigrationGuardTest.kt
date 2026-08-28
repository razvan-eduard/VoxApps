package com.voxapps.expenses.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The recipients table's schema plumbing, pinned at the source — the precedent of
 * [com.voxapps.expenses.domain.llm.ScanPathsHonourTheSettingTest]: there is no Room migration
 * harness under `src/test`, `exportSchema` is off, and a migration missing from the
 * `addMigrations` chain surfaces only as a runtime crash on the first upgraded install. These
 * assertions are the cheapest thing that fails at build time instead.
 */
class RecipientsMigrationGuardTest {

    private fun source(): String =
        listOf(
            "src/main/java/com/voxapps/expenses/data/ExpensesDatabase.kt",
            "vox-expenses/src/main/java/com/voxapps/expenses/data/ExpensesDatabase.kt"
        ).map(::File).first { it.exists() }.readText()

    @Test
    fun `the database is at 41 with the recipients entity`() {
        val text = source()
        assertTrue("version must be 41", text.contains("version = 41"))
        assertTrue("Recipient must be a Room entity", text.contains("Recipient::class"))
        assertTrue("recipientDao accessor must exist", text.contains("fun recipientDao(): RecipientDao"))
    }

    @Test
    fun `the migration exists, is chained, and adds the expense column`() {
        val text = source()
        assertTrue(text.contains("MIGRATION_40_41"))
        assertTrue(
            "MIGRATION_40_41 must be in the addMigrations chain",
            Regex("""addMigrations\([^)]*MIGRATION_40_41""").containsMatchIn(text)
        )
        assertTrue(text.contains("CREATE TABLE IF NOT EXISTS recipients"))
        assertTrue(text.contains("CREATE UNIQUE INDEX IF NOT EXISTS index_recipients_iban"))
        assertTrue(text.contains("ALTER TABLE expenses ADD COLUMN recipientId INTEGER"))
    }
}
