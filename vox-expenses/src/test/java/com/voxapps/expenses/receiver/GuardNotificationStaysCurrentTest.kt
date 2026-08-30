package com.voxapps.expenses.receiver

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The standing notification's figures are windows on the clock as much as on the ledger: "today"
 * moves at midnight whether or not a record does. Checked against the source, like the voice-path
 * guards — what matters is which flows feed the one collector, and that is a property of the code.
 */
class GuardNotificationStaysCurrentTest {

    private fun source(path: String): String =
        listOf(path, "vox-expenses/$path").map(::File).first { it.exists() }.readText()
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("import ") || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    @Test
    fun `the ledger, the queue, the settings and the clock all feed the panel`() {
        val service = source("src/main/java/com/voxapps/expenses/receiver/RescanGuardService.kt")
        assertTrue(
            "upserts and deletes redraw through the expenses flow",
            service.contains("expensesRepository.expenses")
        )
        assertTrue(
            "midnight redraws the day windows without a record moving",
            service.contains("midnightTicks()")
        )
    }

    @Test
    fun `nudges reuse the one collector instead of stacking new ones`() {
        val service = source("src/main/java/com/voxapps/expenses/receiver/RescanGuardService.kt")
        assertTrue(service.contains("if (observing) return"))
    }

    @Test
    fun `the midnight worker also nudges a service the OEM killed`() {
        val worker = source("src/main/java/com/voxapps/expenses/domain/widget/WidgetMidnightRefreshWorker.kt")
        assertTrue(worker.contains("RescanGuard.startIfNeeded("))
    }
}
