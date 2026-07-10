package com.voxapps.notes.data

import kotlin.random.Random

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

    /**
     * The first preset palette color not already used by [existingColors], or — once all 10 are
     * taken — a freshly generated color: random hue, fixed saturation/value matching the palette's
     * vivid, mid-brightness style so it doesn't look out of place next to the presets.
     */
    fun unusedOrRandomColor(existingColors: List<Long>): Long {
        val used = existingColors.toSet()
        argb.firstOrNull { it !in used }?.let { return it }
        return hsvToArgb(hue = Random.nextFloat() * 360f, saturation = 0.55f, value = 0.85f)
    }

    /** Standard HSV->RGB conversion, packed as an opaque ARGB Long. No Compose dependency. */
    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = value - c
        val (rP, gP, bP) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rP + m) * 255).toInt().coerceIn(0, 255)
        val g = ((gP + m) * 255).toInt().coerceIn(0, 255)
        val b = ((bP + m) * 255).toInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }
}
