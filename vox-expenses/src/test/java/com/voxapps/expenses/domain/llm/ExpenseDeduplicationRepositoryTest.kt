package com.voxapps.expenses.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpenseDeduplicationRepositoryTest {

    @Test
    fun `re-running the same check does not grow the pending list`() {
        // Reproduces the reported bug: repeated taps of "Check for duplicates now" re-detected the
        // same anchor expense every time, and each tap appended a fresh, structurally-identical group
        // instead of recognizing it as the same suggestion already pending.
        val first = listOf(DuplicateGroup(keepId = 1L, duplicateIds = listOf(2L, 3L)))
        val second = listOf(DuplicateGroup(keepId = 1L, duplicateIds = listOf(2L, 3L)))

        val afterFirst = mergeGroupsByKeepId(emptyList(), first)
        val afterSecond = mergeGroupsByKeepId(afterFirst, second)

        assertEquals(listOf(DuplicateGroup(keepId = 1L, duplicateIds = listOf(2L, 3L))), afterSecond)
    }

    @Test
    fun `a later run that finds an additional duplicate for the same anchor unions the ids`() {
        val first = listOf(DuplicateGroup(keepId = 1L, duplicateIds = listOf(2L)))
        val second = listOf(DuplicateGroup(keepId = 1L, duplicateIds = listOf(3L)))

        val merged = mergeGroupsByKeepId(first, second)

        assertEquals(listOf(DuplicateGroup(keepId = 1L, duplicateIds = listOf(2L, 3L))), merged)
    }

    @Test
    fun `groups anchored to different kept expenses stay separate`() {
        val current = listOf(DuplicateGroup(keepId = 1L, duplicateIds = listOf(2L)))
        val newGroups = listOf(DuplicateGroup(keepId = 5L, duplicateIds = listOf(6L)))

        val merged = mergeGroupsByKeepId(current, newGroups)

        assertEquals(
            listOf(
                DuplicateGroup(keepId = 1L, duplicateIds = listOf(2L)),
                DuplicateGroup(keepId = 5L, duplicateIds = listOf(6L))
            ),
            merged
        )
    }
}
