package com.voxapps.design.effects.particles

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmitterShapeTest {

    private val size = Size(200f, 100f)

    @Test
    fun `Rectangle spawns anywhere within bounds`() {
        repeat(50) {
            val point = EmitterShape.Rectangle.randomPoint(size)
            assertTrue(point.x in 0f..size.width)
            assertTrue(point.y in 0f..size.height)
        }
    }

    @Test
    fun `Ring spawns only on the inset perimeter, never in the interior`() {
        val ring = EmitterShape.Ring(insetFraction = 0.1f)
        val insetX = size.width * 0.1f
        val insetY = size.height * 0.1f

        repeat(50) {
            val point = ring.randomPoint(size)
            val onVerticalEdge = point.x == insetX || point.x == size.width - insetX
            val onHorizontalEdge = point.y == insetY || point.y == size.height - insetY
            assertTrue("point $point should lie on the inset perimeter", onVerticalEdge || onHorizontalEdge)
        }
    }

    @Test
    fun `Line spawns points on the straight segment between its two fractional endpoints`() {
        // Diagonal from top-left to bottom-right, in fractional (0f..1f) space.
        val line = EmitterShape.Line(from = Offset(0f, 0f), to = Offset(1f, 1f))

        repeat(50) {
            val point = line.randomPoint(size)
            // On this particular diagonal, x/width and y/height must match (within float tolerance).
            val tFromX = point.x / size.width
            val tFromY = point.y / size.height
            assertEquals(tFromX, tFromY, 1e-3f)
            assertTrue(point.x in 0f..size.width)
            assertTrue(point.y in 0f..size.height)
        }
    }

    @Test
    fun `Point always resolves to the same fixed fractional position`() {
        val point = EmitterShape.Point(Offset(0.25f, 0.75f))

        val resolved = point.randomPoint(size)

        assertEquals(size.width * 0.25f, resolved.x, 1e-3f)
        assertEquals(size.height * 0.75f, resolved.y, 1e-3f)
    }
}
