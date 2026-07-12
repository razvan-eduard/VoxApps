package com.voxapps.calendarapp.state

import com.voxapps.calendarapp.data.CalendarEntry
import com.voxapps.calendarapp.data.CalendarEntryType
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarEntryTag
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarFilterTest {

    private fun entry(id: Long, layerId: Long, tags: List<String> = emptyList()) = CalendarEntryWithTags(
        entry = CalendarEntry(
            id = id,
            uid = "uid-$id",
            type = CalendarEntryType.EVENT,
            title = "Entry $id",
            startMillis = 0L,
            layerId = layerId,
            createdAt = 0L,
            updatedAt = 0L
        ),
        tags = tags.map { CalendarEntryTag(entryId = id, tagName = it) }
    )

    @Test
    fun `only entries from visible layers are kept`() {
        val entries = listOf(entry(1, layerId = 10), entry(2, layerId = 20))
        val result = CalendarFilter.apply(entries, visibleLayerIds = setOf(10), selectedTags = emptySet())
        assertEquals(listOf(1L), result.map { it.entry.id })
    }

    @Test
    fun `empty tag selection keeps every visible-layer entry regardless of tags`() {
        val entries = listOf(entry(1, layerId = 10, tags = listOf("Medical")), entry(2, layerId = 10))
        val result = CalendarFilter.apply(entries, visibleLayerIds = setOf(10), selectedTags = emptySet())
        assertEquals(2, result.size)
    }

    @Test
    fun `non-empty tag selection requires at least one matching tag`() {
        val entries = listOf(
            entry(1, layerId = 10, tags = listOf("Medical")),
            entry(2, layerId = 10, tags = listOf("Bills")),
            entry(3, layerId = 10)
        )
        val result = CalendarFilter.apply(entries, visibleLayerIds = setOf(10), selectedTags = setOf("Medical"))
        assertEquals(listOf(1L), result.map { it.entry.id })
    }

    @Test
    fun `layer visibility and tag filters combine`() {
        val entries = listOf(
            entry(1, layerId = 10, tags = listOf("Medical")),
            entry(2, layerId = 20, tags = listOf("Medical"))
        )
        val result = CalendarFilter.apply(entries, visibleLayerIds = setOf(10), selectedTags = setOf("Medical"))
        assertEquals(listOf(1L), result.map { it.entry.id })
    }
}
