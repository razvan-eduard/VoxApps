package com.voxapps.hub.domain.backup

import com.voxapps.ipc.VoxResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupExportRequestTest {

    @Test
    fun `expenses receipts zip keeps its exact legacy entry name`() {
        val result = VoxResult(ok = true, text = "{}", attachmentUri = "content://receipts")
        val entries = zipEntriesFor("expenses", result)
        assertEquals(mapOf("expenses-receipts.zip" to "content://receipts"), entries)
    }

    @Test
    fun `other domains use the dollar-domain-attachments naming for the primary attachment uri`() {
        val result = VoxResult(ok = true, text = "{}", attachmentUri = "content://notes-attachments")
        val entries = zipEntriesFor("notes", result)
        assertEquals(mapOf("notes-attachments.zip" to "content://notes-attachments"), entries)
    }

    @Test
    fun `expenses can contribute both its legacy receipts zip and a separate attachments zip`() {
        val result = VoxResult(
            ok = true, text = "{}",
            attachmentUri = "content://receipts",
            secondaryAttachmentUri = "content://attachments"
        )
        val entries = zipEntriesFor("expenses", result)
        assertEquals(
            mapOf(
                "expenses-receipts.zip" to "content://receipts",
                "expenses-attachments.zip" to "content://attachments"
            ),
            entries
        )
    }

    @Test
    fun `no attachment uris means an empty entry map`() {
        val result = VoxResult(ok = true, text = "{}")
        assertEquals(emptyMap<String, String>(), zipEntriesFor("expenses", result))
        assertEquals(emptyMap<String, String>(), zipEntriesFor("notes", result))
    }
}
