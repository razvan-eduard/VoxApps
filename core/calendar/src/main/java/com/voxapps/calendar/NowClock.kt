package com.voxapps.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * The current time, as something composition can depend on.
 *
 * `System.currentTimeMillis()` read inside a composable is a value, not a source: it is sampled when
 * that composable happens to run and never again. Anything drawn from it — a "now" line on a
 * timeline, the clock beside it, whether an item counts as past — then sits still while the actual
 * clock moves, and only jumps when something *else* causes a recomposition. A list that nobody
 * touches keeps yesterday's answer.
 *
 * This ticks on the minute *boundary* rather than every sixty seconds from whenever it started, so
 * the label changes when the wall clock changes rather than up to a minute later, and repeated
 * ticks cannot drift away from it.
 */
@Composable
fun rememberNowMillis(intervalMillis: Long = MINUTE_MILLIS): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), intervalMillis) {
        while (true) {
            val now = System.currentTimeMillis()
            delay(intervalMillis - (now % intervalMillis))
            value = System.currentTimeMillis()
        }
    }

private const val MINUTE_MILLIS = 60_000L
