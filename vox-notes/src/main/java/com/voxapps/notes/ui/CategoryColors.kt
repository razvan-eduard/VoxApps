package com.voxapps.notes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Preset palette offered in the "Add category" dialog. Stored as a packed ARGB int (in a [Long]). */
object CategoryColors {
    val palette: List<Color> = listOf(
        Color(0xFFEF5350), // red
        Color(0xFFEC407A), // pink
        Color(0xFFAB47BC), // purple
        Color(0xFF5C6BC0), // indigo
        Color(0xFF42A5F5), // blue
        Color(0xFF26A69A), // teal
        Color(0xFF66BB6A), // green
        Color(0xFFFFCA28), // amber
        Color(0xFFFF7043), // deep orange
        Color(0xFF8D6E63)  // brown
    )

    /** Packed ARGB int widened to Long for storage. */
    fun toStored(color: Color): Long = color.toArgb().toLong()

    /** Reads back a stored ARGB value (mask keeps only the low 32 bits). */
    fun fromStored(argb: Long): Color = Color(argb.toInt())

    val default: Color get() = palette[2]
}
