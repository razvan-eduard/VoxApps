package com.voxapps.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Existing(val id: Long, val createdAt: Long)
private data class Imported(val name: String)

class VoxSnapshotReplaceImporterTest {

    @Test
    fun `inserts every imported item`() = runTest {
        val inserted = mutableListOf<Imported>()
        val count = VoxSnapshotReplaceImporter.restore(
            mode = VoxImportMode.MERGE,
            imported = listOf(Imported("a"), Imported("b")),
            preExisting = emptyList<Existing>(),
            exportedAt = 100L,
            createdAtOf = { it.createdAt },
            insert = { inserted.add(it); 1L },
            delete = {}
        )
        assertEquals(2, count)
        assertEquals(listOf(Imported("a"), Imported("b")), inserted)
    }

    @Test
    fun `insert returning non-positive is not counted`() = runTest {
        val count = VoxSnapshotReplaceImporter.restore(
            mode = VoxImportMode.MERGE,
            imported = listOf(Imported("bad"), Imported("good")),
            preExisting = emptyList<Existing>(),
            exportedAt = 100L,
            createdAtOf = { it.createdAt },
            insert = { if (it.name == "bad") 0L else 1L },
            delete = {}
        )
        assertEquals(1, count)
    }

    @Test
    fun `MERGE deletes only pre-existing rows created at or before exportedAt`() = runTest {
        val deleted = mutableListOf<Existing>()
        val preExisting = listOf(
            Existing(1, createdAt = 50L),   // before backup — deleted
            Existing(2, createdAt = 100L),  // exactly at exportedAt — deleted (inclusive)
            Existing(3, createdAt = 150L)   // created after backup, on this device — survives
        )
        VoxSnapshotReplaceImporter.restore(
            mode = VoxImportMode.MERGE,
            imported = emptyList<Imported>(),
            preExisting = preExisting,
            exportedAt = 100L,
            createdAtOf = { it.createdAt },
            insert = { 1L },
            delete = { deleted.add(it) }
        )
        assertEquals(setOf(1L, 2L), deleted.map { it.id }.toSet())
    }

    @Test
    fun `FULL_OVERRIDE deletes every pre-existing row regardless of createdAt`() = runTest {
        val deleted = mutableListOf<Existing>()
        val preExisting = listOf(Existing(1, createdAt = Long.MAX_VALUE - 1), Existing(2, createdAt = 0L))
        val count = VoxSnapshotReplaceImporter.restore(
            mode = VoxImportMode.FULL_OVERRIDE,
            imported = listOf(Imported("a")),
            preExisting = preExisting,
            insert = { 1L },
            delete = { deleted.add(it) }
        )
        assertEquals(1, count)
        assertEquals(2, deleted.size)
        assertTrue(deleted.containsAll(preExisting))
    }

    @Test
    fun `ADDITIVE never deletes anything`() = runTest {
        val deleted = mutableListOf<Existing>()
        val preExisting = listOf(Existing(1, createdAt = 0L), Existing(2, createdAt = Long.MAX_VALUE))
        val count = VoxSnapshotReplaceImporter.restore(
            mode = VoxImportMode.ADDITIVE,
            imported = listOf(Imported("a"), Imported("b")),
            preExisting = preExisting,
            insert = { 1L },
            delete = { deleted.add(it) }
        )
        assertEquals(2, count)
        assertTrue(deleted.isEmpty())
    }
}
