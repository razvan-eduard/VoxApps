package com.voxapps.calendarapp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.voxapps.design.color.VoxColorPalette

/** Preset palette offered in the "Add layer" dialog. Stored as a packed ARGB int (in a [Long]). */
object LayerColors {
    val palette: List<Color> = VoxColorPalette.presets.map { Color(it.toInt()) }

    /**
     * Packed ARGB int widened to Long for storage — masked to the low 32 bits rather than a plain
     * `Int.toLong()` widening, which would sign-extend the alpha byte into a huge negative Long that
     * numerically differs from [com.voxapps.calendarapp.data.CalendarLayerPalette]'s positive Long
     * literals for the exact same color (same gotcha vox-expenses' CategoryColors documents).
     */
    fun toStored(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    /** Reads back a stored ARGB value (mask keeps only the low 32 bits). */
    fun fromStored(argb: Long): Color = Color(argb.toInt())

    val default: Color get() = palette[0]
}
