package com.voxapps.design.selection

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * What is picked out of a list, and the handful of rules that go with picking things out of a list.
 *
 * Holding one another is what a list, a calendar and an archive were each doing separately, in the
 * same five lines, with the same four rules: holding a row starts a selection, tapping one adds or
 * removes it, unpicking the last one leaves the mode, and back leaves it before it leaves the
 * screen. Written three times they were three chances to get the last one wrong.
 *
 * There is no separate "is a selection happening" flag. It is whether anything is selected — the
 * two were always changed together, and a mode with nothing in it is a mode with nothing to do.
 */
@Stable
class VoxSelection<T> internal constructor() {

    var ids: Set<T> by mutableStateOf(emptySet())
        private set

    val active: Boolean get() = ids.isNotEmpty()
    val size: Int get() = ids.size

    operator fun contains(id: T): Boolean = id in ids

    /** Holding a row: begins a selection of that one, and does nothing once one is under way — the
     *  press that started it must not also count as a second gesture on top of it. */
    fun start(id: T) {
        if (!active) ids = setOf(id)
    }

    fun toggle(id: T) {
        ids = if (id in ids) ids - id else ids + id
    }

    /** Tapping a row: part of the selection while one is happening, and whatever the row normally
     *  does when none is. */
    inline fun tap(id: T, otherwise: () -> Unit) {
        if (active) toggle(id) else otherwise()
    }

    fun selectAll(all: Collection<T>) {
        ids = all.toSet()
    }

    fun clear() {
        ids = emptySet()
    }
}

@Composable
fun <T> rememberVoxSelection(): VoxSelection<T> = remember { VoxSelection() }

/**
 * Back leaves the selection rather than the screen.
 *
 * A screen with its own back behaviour — "press back again to exit" and the like — should disable
 * that while [selection] is active rather than rely on which handler was composed last. Two enabled
 * handlers resolve by composition order, which is a rule nobody reading the screen can see.
 */
@Composable
fun VoxSelectionBackHandler(selection: VoxSelection<*>) {
    BackHandler(enabled = selection.active) { selection.clear() }
}
