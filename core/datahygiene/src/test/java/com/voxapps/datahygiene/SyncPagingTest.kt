package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPagingTest {

    private data class Row(val uid: String, val at: Long)

    private fun page(
        entries: List<Row>,
        tombstones: List<Row> = emptyList(),
        cursor: String? = null,
        limit: Int? = null
    ) = SyncPaging.page(
        entries, tombstones, cursor, limit,
        entryKey = { SyncPaging.Key(it.at, it.uid) },
        tombstoneKey = { SyncPaging.Key(it.at, it.uid) }
    )

    /** Walks every page the way an exporter does, returning what each page carried in order. */
    private fun drain(
        entries: List<Row>,
        tombstones: List<Row> = emptyList(),
        limit: Int
    ): Pair<List<String>, List<String>> {
        val seenEntries = mutableListOf<String>()
        val seenTombstones = mutableListOf<String>()
        var cursor: String? = null
        // Bounded so a cursor that fails to advance fails the test instead of hanging it.
        repeat(entries.size + tombstones.size + 2) {
            val p = page(entries, tombstones, cursor, limit)
            seenEntries += p.entries.map { it.uid }
            seenTombstones += p.tombstones.map { it.uid }
            cursor = p.nextCursor ?: return seenEntries to seenTombstones
        }
        throw AssertionError("paging never terminated")
    }

    @Test
    fun `no limit returns everything in one page`() {
        val rows = listOf(Row("b", 2), Row("a", 1))

        val p = page(rows, limit = null)

        assertEquals(listOf("a", "b"), p.entries.map { it.uid })
        assertNull(p.nextCursor)
    }

    @Test
    fun `pages are ordered by timestamp then uid`() {
        val rows = listOf(Row("z", 5), Row("b", 1), Row("a", 1))

        val p = page(rows, limit = 3)

        assertEquals(listOf("a", "b", "z"), p.entries.map { it.uid })
    }

    @Test
    fun `every entry is delivered exactly once across pages`() {
        val rows = (1..7).map { Row("e$it", it * 10L) }

        val (entries, _) = drain(rows, limit = 3)

        assertEquals(rows.map { it.uid }, entries)
    }

    @Test
    fun `rows sharing a timestamp still page without repeating or skipping`() {
        val rows = listOf(Row("a", 100), Row("b", 100), Row("c", 100), Row("d", 100))

        val (entries, _) = drain(rows, limit = 2)

        assertEquals(listOf("a", "b", "c", "d"), entries)
    }

    @Test
    fun `tombstones follow the entries and are delivered exactly once`() {
        val entries = (1..3).map { Row("e$it", it * 10L) }
        val tombstones = (1..3).map { Row("t$it", it * 10L) }

        val (gotEntries, gotTombstones) = drain(entries, tombstones, limit = 2)

        assertEquals(listOf("e1", "e2", "e3"), gotEntries)
        assertEquals(listOf("t1", "t2", "t3"), gotTombstones)
    }

    @Test
    fun `a page carries no tombstones while entries are still outstanding`() {
        val entries = (1..4).map { Row("e$it", it * 10L) }
        val tombstones = listOf(Row("t1", 5L))

        val p = page(entries, tombstones, limit = 2)

        assertEquals(listOf("e1", "e2"), p.entries.map { it.uid })
        assertEquals(emptyList<String>(), p.tombstones.map { it.uid })
    }

    @Test
    fun `a delta that fits in one page names no continuation`() {
        val p = page(listOf(Row("a", 1)), listOf(Row("t", 2)), limit = 5)

        assertEquals(listOf("a"), p.entries.map { it.uid })
        assertEquals(listOf("t"), p.tombstones.map { it.uid })
        assertNull(p.nextCursor)
    }

    @Test
    fun `an empty delta names no continuation`() {
        val p = page(emptyList(), emptyList(), limit = 10)

        assertEquals(emptyList<String>(), p.entries.map { it.uid })
        assertNull(p.nextCursor)
    }

    @Test
    fun `tombstones alone page correctly when there are no entries`() {
        val tombstones = (1..5).map { Row("t$it", it * 10L) }

        val (_, gotTombstones) = drain(emptyList(), tombstones, limit = 2)

        assertEquals(tombstones.map { it.uid }, gotTombstones)
    }

    @Test
    fun `an unreadable cursor restarts the delta rather than losing it`() {
        val rows = listOf(Row("a", 1), Row("b", 2))

        val p = page(rows, cursor = "nonsense", limit = 5)

        assertEquals(listOf("a", "b"), p.entries.map { it.uid })
    }

    @Test
    fun `the exported maximum is what a watermark advances to`() {
        assertEquals(300L, SyncPaging.maxTimestamp(listOf(100L, 300L), listOf(200L)))
        assertNull(SyncPaging.maxTimestamp(emptyList(), emptyList()))
    }
}
