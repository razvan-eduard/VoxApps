package com.voxapps.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.voxapps.design.category.VoxCategoryFieldStrings
import com.voxapps.design.color.VoxColorPalette

/**
 * Preset palette offered when creating a category.
 *
 * The palette and its storage encoding both live in [VoxColorPalette] — this names them in the
 * language of this screen. The encoding in particular was written out here three times, once per
 * app, each with the same comment explaining the same sign-extension trap.
 */
object CategoryColors {
    val palette: List<Color> get() = VoxColorPalette.paletteColors

    fun toStored(color: Color): Long = VoxColorPalette.toStored(color)

    fun fromStored(argb: Long): Color = VoxColorPalette.fromStored(argb)

    val default: Color get() = palette[0]
}

/**
 * The category fields, in this app's language.
 *
 * No icon: a note's category carries a name and a colour and nothing else, so the shared fields are
 * asked for without that slot rather than shown an empty one.
 */
@Composable
fun rememberCategoryFieldStrings(): VoxCategoryFieldStrings {
    val languageManager = LocalLanguageManager.current
    return remember(languageManager) {
        VoxCategoryFieldStrings(
            nameLabel = languageManager.getString("category_name"),
            iconTitle = "",
            iconNone = "",
            iconCustom = "",
            save = languageManager.getString("save"),
            cancel = languageManager.getString("cancel"),
            customColorTitle = languageManager.getString("custom_color_title"),
            customColorUse = languageManager.getString("use_color_button"),
            customColorHue = languageManager.getString("hue_label"),
            customColorSaturation = languageManager.getString("saturation_label"),
            customColorBrightness = languageManager.getString("brightness_label")
        )
    }
}
