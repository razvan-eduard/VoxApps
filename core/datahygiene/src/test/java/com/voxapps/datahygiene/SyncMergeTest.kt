package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class DummySyncRecord(val uid: String, val updatedAt: Long, val value: String)

private object DummySyncIdentity : SyncIdentity<DummySyncRecord> {
    override fun uidOf(record: DummySyncRecord): String = record.uid
    override fun updatedAtOf(record: DummySyncRecord): Long = record.updatedAt
}

class SyncMergeTest {

    @Test
    fun `a remote uid not present locally is inserted`() {
        val local = emptyList<DummySyncRecord>()
        val remote = listOf(DummySyncRecord("a", 100, "new"))
        val plan = DummySyncIdentity.planMerge(local, remote, emptySet())
        assertEquals(listOf(remote[0]), plan.toInsert)
        assertTrue(plan.toUpdate.isEmpty())
        assertTrue(plan.toDeleteUids.isEmpty())
    }

    @Test
    fun `a strictly newer remote updatedAt overwrites the local record`() {
        val local = listOf(DummySyncRecord("a", 100, "old"))
        val remote = listOf(DummySyncRecord("a", 200, "new"))
        val plan = DummySyncIdentity.planMerge(local, remote, emptySet())
        assertEquals(listOf(remote[0]), plan.toUpdate)
        assertTrue(plan.toInsert.isEmpty())
    }

    @Test
    fun `an older or equal remote updatedAt is ignored, local wins`() {
        val local = listOf(DummySyncRecord("a", 200, "local-wins"))
        val olderRemote = listOf(DummySyncRecord("a", 100, "stale"))
        val tieRemote = listOf(DummySyncRecord("a", 200, "tie"))

        assertTrue(DummySyncIdentity.planMerge(local, olderRemote, emptySet()).toUpdate.isEmpty())
        assertTrue(DummySyncIdentity.planMerge(local, tieRemote, emptySet()).toUpdate.isEmpty())
    }

    @Test
    fun `a tombstone deletes a uid still present locally`() {
        val local = listOf(DummySyncRecord("a", 100, "will be deleted"))
        val plan = DummySyncIdentity.planMerge(local, emptyList(), setOf("a"))
        assertEquals(listOf("a"), plan.toDeleteUids)
    }

    @Test
    fun `a tombstone for a uid never seen locally is a silent no-op`() {
        val local = emptyList<DummySyncRecord>()
        val plan = DummySyncIdentity.planMerge(local, emptyList(), setOf("never-existed"))
        assertTrue(plan.toDeleteUids.isEmpty())
    }

    @Test
    fun `a tombstone wins over an update for the same uid in the same delta`() {
        val local = listOf(DummySyncRecord("a", 100, "old"))
        val remote = listOf(DummySyncRecord("a", 200, "conflicting update"))
        val plan = DummySyncIdentity.planMerge(local, remote, setOf("a"))
        assertTrue(plan.toUpdate.isEmpty())
        assertEquals(listOf("a"), plan.toDeleteUids)
    }

    @Test
    fun `a mixed delta inserts, updates, and deletes independently`() {
        val local = listOf(
            DummySyncRecord("keep-newer-local", 500, "local"),
            DummySyncRecord("gets-updated", 100, "old"),
            DummySyncRecord("gets-deleted", 100, "bye")
        )
        val remote = listOf(
            DummySyncRecord("brand-new", 100, "inserted"),
            DummySyncRecord("keep-newer-local", 200, "stale remote"),
            DummySyncRecord("gets-updated", 300, "fresh")
        )
        val plan = DummySyncIdentity.planMerge(local, remote, setOf("gets-deleted"))

        assertEquals(listOf(remote[0]), plan.toInsert)
        assertEquals(listOf(remote[2]), plan.toUpdate)
        assertEquals(listOf("gets-deleted"), plan.toDeleteUids)
    }
}
