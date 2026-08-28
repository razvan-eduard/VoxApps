package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingFileScanTaskTest {

    @Test
    fun `builds and parses a PDF task round trip`() {
        val task = PendingFileScanTask.build("att_1.pdf", listOf("att_2.jpg", "att_3.jpg"))
        assertEquals("EXPENSE_SCAN_CLEANUP:pending-create-file:att_1.pdf:att_2.jpg,att_3.jpg", task)
        val parsed = PendingFileScanTask.parse(task.split(":"))!!
        assertEquals("att_1.pdf", parsed.originalFileName)
        assertEquals(listOf("att_2.jpg", "att_3.jpg"), parsed.pageFileNames)
    }

    @Test
    fun `an image pick's original is its own single page and linkNames collapses them`() {
        val task = PendingFileScanTask.build("att_9.jpg", listOf("att_9.jpg"))
        val parsed = PendingFileScanTask.parse(task.split(":"))!!
        assertEquals(listOf("att_9.jpg"), PendingFileScanTask.linkNames(parsed))
    }

    @Test
    fun `linkNames puts the original first for a PDF group`() {
        val parsed = PendingFileScanTask.Parsed("att_1.pdf", listOf("att_2.jpg", "att_3.jpg"))
        assertEquals(listOf("att_1.pdf", "att_2.jpg", "att_3.jpg"), PendingFileScanTask.linkNames(parsed))
    }

    @Test
    fun `staged names with several dots survive the round trip`() {
        val task = PendingFileScanTask.build("att_a.b.pdf", listOf("att_c.d.jpg"))
        val parsed = PendingFileScanTask.parse(task.split(":"))!!
        assertEquals("att_a.b.pdf", parsed.originalFileName)
        assertEquals(listOf("att_c.d.jpg"), parsed.pageFileNames)
    }

    @Test
    fun `malformed part counts and foreign tasks parse to null`() {
        assertNull(PendingFileScanTask.parse("EXPENSE_SCAN_CLEANUP:pending-create-file:att_1.pdf".split(":")))
        assertNull(PendingFileScanTask.parse("EXPENSE_SCAN_CLEANUP:pending-create:att_1.pdf:att_2.jpg".split(":")))
        assertNull(PendingFileScanTask.parse("OTHER_TASK:pending-create-file:att_1.pdf:att_2.jpg".split(":")))
        assertNull(PendingFileScanTask.parse("EXPENSE_SCAN_CLEANUP:pending-create-file::att_2.jpg".split(":")))
        assertNull(PendingFileScanTask.parse("EXPENSE_SCAN_CLEANUP:pending-create-file:att_1.pdf:".split(":")))
    }
}
