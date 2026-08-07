package com.voxapps.design.color

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Shape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Swatch outlines usable as the `shape` argument of [VoxColorSwatchPicker] (and anywhere else a
 * colored marker needs to carry a second meaning beyond its color).
 *
 * The picker defaults to [androidx.compose.foundation.shape.CircleShape]; passing [Star] instead
 * lets a caller mark a whole picker as "special" without adding a boolean the shared component would
 * have to interpret — vox-calendar uses it so the Main calendar's color reads as a star everywhere
 * it appears (picker swatches and the sidebar dot alike).
 */
object VoxSwatchShapes {

    /** Ratio of the inner (valley) radius to the outer (point) radius. 0.42 keeps the arms broad
     *  enough to still read as a solid color fill at the ~12dp sizes the sidebar uses, where a
     *  spindlier star turns into visual noise. */
    private const val INNER_RADIUS_RATIO = 0.42f
    private const val POINTS = 5

    /** A 5-pointed star inscribed in the layout bounds, first point straight up. */
    val Star: Shape = GenericShape { size, _ ->
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val outerRadius = min(size.width, size.height) / 2f
        val innerRadius = outerRadius * INNER_RADIUS_RATIO
        // Start at -90° so a point faces up rather than the flat side of the star.
        val startAngle = -PI.toFloat() / 2f
        // Half-step between successive vertices, since the path alternates outer/inner.
        val angleStep = PI.toFloat() / POINTS

        for (i in 0 until POINTS * 2) {
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angle = startAngle + angleStep * i
            val x = centerX + radius * cos(angle)
            val y = centerY + radius * sin(angle)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
