package com.voxapps.calendarapp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Preset palette offered in the "Add layer" dialog. Stored as a packed ARGB int (in a [Long]). */
object LayerColors {
    val palette: List<Color> = listOf(
        Color(0xFF5C6BC0), // indigo
        Color(0xFFEF5350), // red
        Color(0xFF66BB6A), // green
        Color(0xFFFFCA28), // amber
        Color(0xFF26A69A), // teal
        Color(0xFFEC407A), // pink
        Color(0xFF42A5F5), // blue
        Color(0xFFAB47BC), // purple
        Color(0xFFFF7043), // deep orange
        Color(0xFF8D6E63)  // brown
    )

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
