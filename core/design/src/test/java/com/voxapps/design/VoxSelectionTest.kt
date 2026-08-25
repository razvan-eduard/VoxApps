package com.voxapps.design

import com.voxapps.design.selection.VoxSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four rules every list that picks things out of itself was writing for itself. */
class VoxSelectionTest {

    private fun selection() = VoxSelection<Long>()

    @Test
    fun `nothing is selected until something is`() {
        val selection = selection()
        assertFalse(selection.active)
        assertEquals(0, selection.size)
    }

    @Test
    fun `holding a row starts a selection of that row`() {
        val selection = selection()
        selection.start(7L)
        assertTrue(selection.active)
        assertTrue(7L in selection)
    }

    /** The press that starts a selection must not also count as a gesture on top of it, or the
     *  first row would be picked and immediately unpicked. */
    @Test
    fun `holding another row while one is under way changes nothing`() {
        val selection = selection()
        selection.start(7L)
        selection.start(9L)
        assertEquals(setOf(7L), selection.ids)
    }

    @Test
    fun `tapping adds and removes while a selection is happening`() {
        val selection = selection()
        selection.start(7L)
        var opened = 0

        selection.tap(9L) { opened++ }
        assertEquals(setOf(7L, 9L), selection.ids)
        selection.tap(9L) { opened++ }
        assertEquals(setOf(7L), selection.ids)
        assertEquals(0, opened)
    }

    @Test
    fun `tapping does the row's ordinary thing when no selection is happening`() {
        val selection = selection()
        var opened = 0

        selection.tap(7L) { opened++ }

        assertEquals(1, opened)
        assertFalse(selection.active)
    }

    /** Unpicking the last one leaves the mode: a selection bar with nothing in it is a mode with
     *  nothing to do. */
    @Test
    fun `unpicking the last row ends the selection`() {
        val selection = selection()
        selection.start(7L)
        selection.toggle(7L)
        assertFalse(selection.active)
    }

    @Test
    fun `select all takes the list it is given, and clear takes nothing`() {
        val selection = selection()
        selection.start(1L)
        selection.selectAll(listOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), selection.ids)

        selection.clear()
        assertFalse(selection.active)
        assertEquals(emptySet<Long>(), selection.ids)
    }
}
