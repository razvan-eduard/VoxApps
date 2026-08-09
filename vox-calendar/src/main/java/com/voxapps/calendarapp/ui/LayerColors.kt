package com.voxapps.calendarapp.ui

import androidx.compose.ui.graphics.Color
import com.voxapps.design.color.VoxColorPalette

/**
 * Preset palette offered when creating a calendar.
 *
 * The palette and its storage encoding both live in [VoxColorPalette] — this names them in the
 * language of this screen. The encoding in particular was written out here three times, once per
 * app, each with the same comment explaining the same sign-extension trap.
 */
object LayerColors {
    val palette: List<Color> get() = VoxColorPalette.paletteColors

    fun toStored(color: Color): Long = VoxColorPalette.toStored(color)

    fun fromStored(argb: Long): Color = VoxColorPalette.fromStored(argb)

    val default: Color get() = palette[0]
}
