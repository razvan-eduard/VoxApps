package com.voxapps.expenses.data

import com.voxapps.design.color.VoxColorPalette

/**
 * Preset category colors as packed ARGB ints (in Longs) — no Compose deps, so the data layer can pick
 * a color for an auto-created category. The UI's `CategoryColors` renders the same values. Thin
 * delegate over the shared [VoxColorPalette] (also used by vox-notes/vox-calendar) — kept as a
 * separate object rather than inlining call sites so existing references (`CategoryPalette.argb`,
 * `CategoryPalette.unusedOrRandomColor(...)`) don't need touching everywhere they're used.
 */
object CategoryPalette {
    val argb: List<Long> get() = VoxColorPalette.presets

    fun unusedOrRandomColor(existingColors: List<Long>, precedingColor: Long? = null): Long =
        VoxColorPalette.unusedOrRandomColor(existingColors, precedingColor)
}
