package com.voxapps.ipc

import org.junit.Assert.assertEquals
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
}
