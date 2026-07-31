package com.voxapps.design.effects.particles

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import kotlin.random.Random

/**
 * Where new particles spawn, in coordinates fractional (`0f`..`1f`) to the wrapped content's
 * measured size — callers describe placement relatively ("along the edge", "from x1,y1 to x2,y2")
 * without knowing pixel dimensions. [randomPoint] resolves the shape to an actual pixel [Offset] for
 * one new particle, given the content's real measured [size].
 */
sealed interface EmitterShape {
    fun randomPoint(size: Size): Offset

    /** Spawns anywhere inside the full bounds. */
    data object Rectangle : EmitterShape {
        override fun randomPoint(size: Size): Offset =
            Offset(Random.nextFloat() * size.width, Random.nextFloat() * size.height)
    }

    /** Spawns along a rectangular perimeter inset by [insetFraction] of the shorter dimension
     *  (`0f` = exact edge). */
    data class Ring(val insetFraction: Float = 0.05f) : EmitterShape {
        override fun randomPoint(size: Size): Offset {
            val insetX = size.width * insetFraction
            val insetY = size.height * insetFraction
            val left = insetX
            val right = (size.width - insetX).coerceAtLeast(left)
            val top = insetY
            val bottom = (size.height - insetY).coerceAtLeast(top)
            return when (Random.nextInt(4)) {
                0 -> Offset(left + Random.nextFloat() * (right - left), top)
                1 -> Offset(left + Random.nextFloat() * (right - left), bottom)
                2 -> Offset(left, top + Random.nextFloat() * (bottom - top))
                else -> Offset(right, top + Random.nextFloat() * (bottom - top))
            }
        }
    }

    /** Spawns along a straight vector from [from] to [to] (fractional `0f`..`1f` coordinates). */
    data class Line(val from: Offset, val to: Offset) : EmitterShape {
        override fun randomPoint(size: Size): Offset {
            val t = Random.nextFloat()
            return Offset(
                (from.x + (to.x - from.x) * t) * size.width,
                (from.y + (to.y - from.y) * t) * size.height
            )
        }
    }

    /** Spawns at a single fixed (fractional) point. */
    data class Point(val position: Offset) : EmitterShape {
        override fun randomPoint(size: Size): Offset =
            Offset(position.x * size.width, position.y * size.height)
    }

    /** Spawns along an arbitrary hand-built [path] — the same geometric expressiveness a Lottie
     *  emitter would have (lines/quadratic/cubic beziers/arcs), written directly in Kotlin instead of
     *  JSON. Build [path] in a `0f`..`1f` unit-square coordinate space; it's scaled to the real
     *  content size here. */
    data class ArbitraryPath(val path: Path) : EmitterShape {
        private val measure = PathMeasure()

        override fun randomPoint(size: Size): Offset {
            measure.setPath(path, false)
            val distance = Random.nextFloat() * measure.length
            val position = measure.getPosition(distance)
            return Offset(position.x * size.width, position.y * size.height)
        }
    }
}
