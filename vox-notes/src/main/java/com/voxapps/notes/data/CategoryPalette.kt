package com.voxapps.notes.data

/**
 * Preset category colors as packed ARGB ints (in Longs) — no Compose deps, so the data layer can pick
 * a color for an auto-created category. The UI's `CategoryColors` renders the same values.
 */
object CategoryPalette {
    val argb: List<Long> = listOf(
        0xFFEF5350L, // red
        0xFFEC407AL, // pink
        0xFFAB47BCL, // purple
        0xFF5C6BC0L, // indigo
        0xFF42A5F5L, // blue
        0xFF26A69AL, // teal
        0xFF66BB6AL, // green
        0xFFFFCA28L, // amber
        0xFFFF7043L, // deep orange
        0xFF8D6E63L  // brown
    )

    /** Color for the Nth category (wraps around the palette). */
    fun colorForIndex(index: Int): Long = argb[((index % argb.size) + argb.size) % argb.size]
}
