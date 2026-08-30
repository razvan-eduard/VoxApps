package com.voxapps.calendar

import androidx.compose.runtime.Stable
import com.voxapps.design.selection.VoxSelection

/**
 * The multi-select contract of [CalendarView]'s agenda list, lifted out of the apps: holding a row
 * starts a selection, tapping one toggles membership while a selection is under way and does the
 * row's ordinary thing when none is — [VoxSelection]'s grammar, applied to calendar items the same
 * way in every app that shows them.
 *
 * The apps keep everything that is genuinely theirs: they own the [VoxSelection] (so the top bar,
 * select-all, and the actions offered over a selection stay app-defined), they say which stable key
 * identifies an item, and their `itemContent` draws its own selected state. What lives here is only
 * the grammar — so a calendar list never selects one way in one app and another way in the next.
 */
@Stable
class CalendarSelection<T>(
    val selection: VoxSelection<Long>,
    val keyOf: (T) -> Long
)

/** One item's slice of the contract, handed to `itemContent`: bind [selected] to the card's look,
 *  [onClick]/[onLongClick] to its gestures, and pass the ordinary open action into [onClick]. */
@Stable
class CalendarItemSelection internal constructor(
    val selected: Boolean,
    private val selection: VoxSelection<Long>,
    private val key: Long
) {
    /** Toggles while a selection is active; runs [open] — the row's ordinary tap — otherwise. */
    fun onClick(open: () -> Unit) = selection.tap(key, open)

    fun onLongClick() = selection.start(key)
}

internal fun <T> CalendarSelection<T>.handlesFor(item: T): CalendarItemSelection {
    val key = keyOf(item)
    return CalendarItemSelection(selected = key in selection, selection = selection, key = key)
}
