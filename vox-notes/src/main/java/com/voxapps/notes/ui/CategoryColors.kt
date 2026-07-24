package com.voxapps.notes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.voxapps.design.color.VoxColorPalette

/** Preset palette offered in the "Add category" dialog. Stored as a packed ARGB int (in a [Long]). */
object CategoryColors {
    val palette: List<Color> = VoxColorPalette.presets.map { Color(it.toInt()) }

    /**
     * Packed ARGB int widened to Long for storage — masked to the low 32 bits rather than a plain
     * `Int.toLong()` widening. Every ARGB color here has its alpha byte set, making [Color.toArgb]
     * negative as a signed Int32; a plain widening sign-extends that into a huge negative Long that
     * numerically differs from [CategoryPalette]'s positive Long literals for the exact same color
     * (e.g. `Color(0xFFEF5350).toArgb().toLong()` != `0xFFEF5350L`), silently breaking every equality
     * check against them (`unusedOrRandomColor`'s dedup, [ColorSwatchRow]'s selected-swatch highlight).
     */
    fun toStored(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    /** Reads back a stored ARGB value (mask keeps only the low 32 bits). */
    fun fromStored(argb: Long): Color = Color(argb.toInt())

    val default: Color get() = palette[2]
}
