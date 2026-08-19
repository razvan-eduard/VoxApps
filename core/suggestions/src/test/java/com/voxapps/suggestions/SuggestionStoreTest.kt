package com.voxapps.suggestions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a suggestion lives by, which were the part that got written differently in each place
 * before there was one place.
 *
 * The source-disposal case is here because the first version of it did nothing at all: it compared
 * two sets computed from the same list, so the difference was always empty and a spent source was
 * never handed back. That is invisible from the outside — the suggestion disappears either way — and
 * it is exactly what leaves photographs attached to a record with nothing left to apply them from.
 */
class SuggestionStoreTest {

    private class FakeDao : FieldSuggestionDao {
        val rows = MutableStateFlow<List<FieldSuggestion>>(emptyList())
        override fun forRecord(recordId: Long): Flow<List<FieldSuggestion>> =
            rows.map { all -> all.filter { it.recordId == recordId } }
        override suspend fun snapshot(recordId: Long): List<FieldSuggestion> =
            rows.value.filter { it.recordId == recordId }
        override suspend fun upsert(suggestions: List<FieldSuggestion>) {
            val keys = suggestions.map { it.recordId to it.fieldKey }.toSet()
            rows.value = rows.value.filterNot { (it.recordId to it.fieldKey) in keys } + suggestions
        }
        override suspend fun clearField(recordId: Long, fieldKey: String) {
            rows.value = rows.value.filterNot { it.recordId == recordId && it.fieldKey == fieldKey }
        }
        override suspend fun clearRecord(recordId: Long) {
            rows.value = rows.value.filterNot { it.recordId == recordId }
        }
        override suspend fun clearSource(recordId: Long, sourceTag: String) {
            rows.value = rows.value.filterNot { it.recordId == recordId && it.sourceTag == sourceTag }
        }
    }

    private class FakeTarget(
        override val suggestableFields: List<SuggestableField> = listOf(
            SuggestableField("vendor", "Vendor"),
            SuggestableField("items", "Line items")
        ),
        val record: MutableMap<String, String?> = mutableMapOf("vendor" to "Old", "items" to null),
        var refuses: Boolean = false
    ) : SuggestionTarget {
        val discarded = mutableListOf<String>()
        override suspend fun currentValue(recordId: Long, fieldKey: String) = record[fieldKey]
        override suspend fun applyValue(recordId: Long, fieldKey: String, value: String?): Boolean {
            if (refuses) return false
            record[fieldKey] = value
            return true
        }
        override suspend fun discardSource(recordId: Long, sourceTag: String) {
            discarded += sourceTag
        }
    }

    @Test
    fun `a field the satellite never declared is not stored`() = runTest {
        val dao = FakeDao()
        val store = SuggestionStore(dao, FakeTarget())
        store.offer(1L, mapOf("vendor" to "New", "weatherOnMars" to "cold"))
        assertEquals(listOf("vendor"), dao.snapshot(1L).map { it.fieldKey })
    }

    @Test
    fun `a proposal equal to what the record says is not offered`() = runTest {
        val dao = FakeDao()
        val target = FakeTarget()
        val store = SuggestionStore(dao, target)
        store.offer(1L, mapOf("vendor" to "Old", "items" to "[…]"))
        assertEquals(listOf("items"), store.offered(1L).first().map { it.field.key })
    }

    @Test
    fun `an accept the satellite could not carry out leaves the offer standing`() = runTest {
        val dao = FakeDao()
        val target = FakeTarget(refuses = true)
        val store = SuggestionStore(dao, target)
        store.offer(1L, mapOf("vendor" to "New"))
        assertFalse(store.accept(1L, "vendor", "New"))
        assertEquals(1, dao.snapshot(1L).size)
    }

    @Test
    fun `accepting writes the value and removes the offer`() = runTest {
        val dao = FakeDao()
        val target = FakeTarget()
        val store = SuggestionStore(dao, target)
        store.offer(1L, mapOf("vendor" to "New"))
        assertTrue(store.accept(1L, "vendor", "New"))
        assertEquals("New", target.record["vendor"])
        assertTrue(dao.snapshot(1L).isEmpty())
    }

    /** The whole point of the tag: the photographs that produced the proposal are disposed of with
     *  the last of it, and not before. */
    @Test
    fun `a source is handed back only once nothing of it remains`() = runTest {
        val dao = FakeDao()
        val target = FakeTarget()
        val store = SuggestionStore(dao, target)
        store.offer(1L, mapOf("vendor" to "New", "items" to "[…]"), sourceTag = "scan-7")

        store.dismiss(1L, "vendor")
        assertEquals(emptyList<String>(), target.discarded)

        store.dismiss(1L, "items")
        assertEquals(listOf("scan-7"), target.discarded)
    }

    @Test
    fun `a suggestion with no source hands nothing back`() = runTest {
        val dao = FakeDao()
        val target = FakeTarget()
        val store = SuggestionStore(dao, target)
        store.offer(1L, mapOf("vendor" to "New"))
        store.dismiss(1L, "vendor")
        assertEquals(emptyList<String>(), target.discarded)
    }

    @Test
    fun `saving the record clears everything it was offered`() = runTest {
        val dao = FakeDao()
        val store = SuggestionStore(dao, FakeTarget())
        store.offer(1L, mapOf("vendor" to "New", "items" to "[…]"), sourceTag = "scan-7")
        store.clear(1L)
        assertTrue(dao.snapshot(1L).isEmpty())
    }
}
