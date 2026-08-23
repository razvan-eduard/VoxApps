package com.voxapps.expenses.ui

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
 * How a category names itself in a line of text: its icon, then its name.
 *
 * One function rather than the check written at each site, so every list, chip, rule and report
 * agrees on what a category looks like. Where a row has a slot of its own for the icon — an expense
 * card, a picklist entry, a widget line — the icon goes in the slot and this is not used.
 */
fun com.voxapps.expenses.data.Category.labelled(): String =
    icon?.let { "$it $name" } ?: name

/**
 * This app's words for the shared category fields — see [VoxCategoryFields].
 *
 * Assembled once rather than at each call site, so the two places a category can be made cannot
 * end up labelling the same field differently.
 */
@Composable
fun rememberCategoryFieldStrings(): VoxCategoryFieldStrings {
    val languageManager = LocalLanguageManager.current
    return remember(languageManager) {
        VoxCategoryFieldStrings(
            nameLabel = languageManager.getString("category_name"),
            iconTitle = languageManager.getString("category_icon_title"),
            iconNone = languageManager.getString("category_icon_none"),
            iconCustom = languageManager.getString("category_icon_custom"),
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
