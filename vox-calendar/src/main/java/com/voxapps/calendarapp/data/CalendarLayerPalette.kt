package com.voxapps.calendarapp.data

import com.voxapps.design.color.VoxColorPalette

/**
 * Preset layer colors as packed ARGB ints (in Longs) — no Compose deps, so the data layer can pick a
 * color for a new layer. The UI's `LayerColors` renders the same values. Thin delegate over the
 * shared [VoxColorPalette] (also used by vox-notes/vox-expenses) — kept as a separate object rather
 * than inlining call sites so existing references (`CalendarLayerPalette.argb`,
 * `CalendarLayerPalette.unusedOrRandomColor(...)`) don't need touching everywhere they're used.
 */
object CalendarLayerPalette {
    val argb: List<Long> get() = VoxColorPalette.presets

    fun unusedOrRandomColor(existingColors: List<Long>, precedingColor: Long? = null): Long =
        VoxColorPalette.unusedOrRandomColor(existingColors, precedingColor)
}
