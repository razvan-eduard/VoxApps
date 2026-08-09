package com.voxapps.design.effects.particles

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.isActive
import kotlin.random.Random

/** Maximum single-frame delta, so resuming from a long-backgrounded pause doesn't dump a huge burst
 *  of "catch-up" spawns/motion into one frame. */
private const val MAX_DELTA_SECONDS = 0.1f

/** Plain (non-Compose-`State`) per-instance bookkeeping — deliberately not `mutableStateOf`, same
 *  reasoning as [particles] below: nothing outside this one draw pass ever reads these, so wrapping
 *  them as observable state would just add overhead with no benefit. */
private class ParticleFieldRuntime {
    var lastFrameNanos = -1L
    var elapsedSeconds = 0f
    var spawnAccumulator = 0f
}

/**
 * Drives one [ParticleEffectConfig] as a self-contained animated layer: a plain (non-observable)
 * particle list ticked by a `withFrameNanos` loop and redrawn every frame via [Canvas] — the
 * generalized form of a hand-rolled fire/particle Canvas loop, minus any effect-specific physics
 * (that all lives in [config]). Fully content-agnostic: fills whatever [modifier] gives it, draws
 * nothing else.
 *
 * [speedMultiplier] scales the time fed into both [ParticleEffectConfig.motion] and
 * [ParticleEffectConfig.color], so every preset speeds up or slows down uniformly without any
 * preset-specific speed handling.
 */
@Composable
fun ParticleField(config: ParticleEffectConfig, modifier: Modifier = Modifier, speedMultiplier: Float = 1f) {
    val particles = remember { mutableListOf<Particle>() }
    val runtime = remember { ParticleFieldRuntime() }
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { nanos -> frameTick = nanos }
        }
    }

    Canvas(modifier = modifier) {
        val nowNanos = frameTick
        val rawDeltaSeconds = if (runtime.lastFrameNanos < 0) {
            0f
        } else {
            ((nowNanos - runtime.lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_DELTA_SECONDS)
        }
        runtime.lastFrameNanos = nowNanos

        val deltaSeconds = rawDeltaSeconds * speedMultiplier
        runtime.elapsedSeconds += deltaSeconds

        runtime.spawnAccumulator += config.spawnRatePerSecond * rawDeltaSeconds
        while (runtime.spawnAccumulator >= 1f && particles.size < config.maxParticles) {
            runtime.spawnAccumulator -= 1f
            val shape = config.emitterShapes[Random.nextInt(config.emitterShapes.size)]
            val spawn = shape.randomPoint(size)
            val radiusSpan = config.initialRadiusRange.endInclusive - config.initialRadiusRange.start
            particles.add(
                Particle(
                    x = spawn.x,
                    y = spawn.y,
                    spawnX = spawn.x,
                    spawnY = spawn.y,
                    radius = config.initialRadiusRange.start + Random.nextFloat() * radiusSpan,
                    life = 1f
                )
            )
        }

        val iterator = particles.listIterator()
        while (iterator.hasNext()) {
            val current = iterator.next()
            val updated = config.motion(current, deltaSeconds, runtime.elapsedSeconds)
            if (updated == null) {
                iterator.remove()
            } else {
                iterator.set(updated)
                drawCircle(
                    color = config.color(updated, updated.life, runtime.elapsedSeconds),
                    radius = updated.radius,
                    center = Offset(updated.x, updated.y)
                )
            }
        }
    }
}
