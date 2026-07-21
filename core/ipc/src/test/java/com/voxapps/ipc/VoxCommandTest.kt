package com.voxapps.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxCommandTest {

    @Test
    fun `create round-trips with all fields`() {
        val cmd = VoxCommand(op = VoxIpc.OP_CREATE, text = "buy milk", title = "Shopping", category = "home")
        val parsed = VoxCommand.fromJson(cmd.toJson())
        assertEquals(cmd, parsed)
    }

    @Test
    fun `read round-trips with optional fields omitted`() {
        val cmd = VoxCommand(op = VoxIpc.OP_READ, limit = 20)
        val parsed = VoxCommand.fromJson(cmd.toJson())!!
        assertEquals(VoxIpc.OP_READ, parsed.op)
        assertEquals(20, parsed.limit)
        assertNull(parsed.text)
        assertNull(parsed.category)
    }

    @Test
    fun `null and blank and malformed payloads parse to null`() {
        assertNull(VoxCommand.fromJson(null))
        assertNull(VoxCommand.fromJson(""))
        assertNull(VoxCommand.fromJson("{ not json"))
        assertNull(VoxCommand.fromJson("{}")) // missing op
    }

    @Test
    fun `VoxResult round-trips`() {
        val ok = VoxResult(ok = true, text = "note a\nnote b")
        assertEquals(ok, VoxResult.fromJson(ok.toJson()))

        val locked = VoxResult(ok = false, text = "Notele sunt blocate.")
        val parsed = VoxResult.fromJson(locked.toJson())!!
        assertTrue(!parsed.ok)
        assertEquals("Notele sunt blocate.", parsed.text)
    }

    @Test
    fun `VoxResult round-trips with an attachmentUri`() {
        val withAttachment = VoxResult(ok = true, text = "{}", attachmentUri = "content://com.voxapps.expenses.fileprovider/exports/x.zip")
        val parsed = VoxResult.fromJson(withAttachment.toJson())!!
        assertEquals("content://com.voxapps.expenses.fileprovider/exports/x.zip", parsed.attachmentUri)
    }

    @Test
    fun `VoxResult backward compat - old payload without attachmentUri parses with null`() {
        val parsed = VoxResult.fromJson("""{"ok":true,"text":"x"}""")!!
        assertNull(parsed.attachmentUri)
    }

    @Test
    fun `includePhotos round-trips true and is omitted when false`() {
        val withPhotos = VoxCommand(op = VoxIpc.OP_EXPORT, includePhotos = true)
        assertTrue(VoxCommand.fromJson(withPhotos.toJson())!!.includePhotos)

        val withoutPhotos = VoxCommand(op = VoxIpc.OP_EXPORT)
        assertFalse(VoxCommand.fromJson(withoutPhotos.toJson())!!.includePhotos)
        assertFalse(withoutPhotos.toJson().contains("includePhotos"))
    }

    @Test
    fun `sync_export round-trips since and scopeNames`() {
        val cmd = VoxCommand(
            op = VoxIpc.OP_SYNC_EXPORT,
            since = 1_700_000_000_000L,
            scopeNames = listOf("Personal", "Work")
        )
        val parsed = VoxCommand.fromJson(cmd.toJson())
        assertEquals(cmd, parsed)
    }

    @Test
    fun `sync_export with since and scopeNames omitted parses to null, not empty`() {
        val cmd = VoxCommand(op = VoxIpc.OP_SYNC_EXPORT)
        val parsed = VoxCommand.fromJson(cmd.toJson())!!
        assertNull(parsed.since)
        assertNull(parsed.scopeNames)
        assertFalse(cmd.toJson().contains("since"))
        assertFalse(cmd.toJson().contains("scopeNames"))
    }

    @Test
    fun `sync_merge carries the delta payload in text`() {
        val cmd = VoxCommand(op = VoxIpc.OP_SYNC_MERGE, text = """{"entries":[],"tombstones":[]}""")
        val parsed = VoxCommand.fromJson(cmd.toJson())
        assertEquals(cmd, parsed)
    }
}
