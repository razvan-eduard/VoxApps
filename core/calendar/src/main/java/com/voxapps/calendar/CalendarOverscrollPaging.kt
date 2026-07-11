package com.voxapps.calendar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Vertical pull distance required, past the day-list's own scroll bounds, to trigger a month
 *  change on release. */
internal val OVERSCROLL_TRIGGER_THRESHOLD_DP = 72.dp
private const val RUBBER_BAND_FACTOR = 0.5f
private const val MAX_PULL_MULTIPLIER = 1.6f

/**
 * A [NestedScrollConnection] that accumulates vertical drag delta the day-list's LazyColumn
 * couldn't itself consume — only once [listState] is already at its scroll bound in the drag
 * direction ([LazyListState.canScrollBackward]/[LazyListState.canScrollForward] false) — into
 * [pullOffsetPx], the shared "pull" value driving both the live rubber-band feedback and the
 * post-release transition ([playOverscrollPageTransition]). This mirrors the exact
 * onPostScroll-leftover-delta pattern Compose's own `PullToRefreshBox` is built on — the standard
 * way to layer "overscroll triggers something else" behavior on top of a scrollable container
 * without fighting its own fling/edge-effect handling (deliberately NOT built on the
 * `OverscrollEffect` API, which is a rendering hook for a single scrollable's own stretch/glow, not
 * meant for triggering unrelated cross-container navigation).
 *
 * Dragging down while already at the top (revealing the previous-month peek further) accumulates a
 * positive pull; dragging up while already at the bottom accumulates a negative pull.
 */
@Composable
internal fun rememberCalendarOverscrollConnection(
    listState: LazyListState,
    pullOffsetPx: Animatable<Float, AnimationVector1D>,
    thresholdPx: Float,
    onTriggerPrevious: suspend () -> Unit,
    onTriggerNext: suspend () -> Unit
): NestedScrollConnection {
    val scope = rememberCoroutineScope()

    return remember(listState, thresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val atTop = !listState.canScrollBackward
                val atBottom = !listState.canScrollForward
                val leftover = available.y
                val movesPull = (leftover > 0f && atTop) || (leftover < 0f && atBottom)
                if (!movesPull) return Offset.Zero

                val newValue = (pullOffsetPx.value + leftover * RUBBER_BAND_FACTOR)
                    .coerceIn(-thresholdPx * MAX_PULL_MULTIPLIER, thresholdPx * MAX_PULL_MULTIPLIER)
                scope.launch { pullOffsetPx.snapTo(newValue) }
                return Offset(0f, leftover)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                when {
                    pullOffsetPx.value >= thresholdPx -> onTriggerPrevious()
                    pullOffsetPx.value <= -thresholdPx -> onTriggerNext()
                    else -> pullOffsetPx.animateTo(0f, tween(200))
                }
                return Velocity.Zero
            }
        }
    }
}

/**
 * The distinct VERTICAL transition for an overscroll-triggered month change — deliberately
 * different from the pager's own horizontal slide used for a manual swipe or a peek-item tap.
 * Continues the same physical pull further off-screen, swaps the page INSTANTLY via [swapPage]
 * (expected to call `PagerState.scrollToPage`, never `animateScrollToPage` — so the pager's own
 * horizontal animation never runs, since the swap happens while content is already faded/slid past
 * full pull), then slides the new page in from the mirrored starting position. [pullOffsetPx] is
 * read by the caller's `Modifier.graphicsLayer` (translationY + alpha) applied to whichever page is
 * currently composed — a single instance hoisted above the pager, since only one page is ever
 * mid-transition at a time.
 */
internal suspend fun playOverscrollPageTransition(
    pullOffsetPx: Animatable<Float, AnimationVector1D>,
    direction: Int, // -1 = previous month, +1 = next month
    thresholdPx: Float,
    swapPage: suspend () -> Unit
) {
    val offscreen = direction * thresholdPx * MAX_PULL_MULTIPLIER
    pullOffsetPx.animateTo(offscreen, tween(140))
    swapPage()
    pullOffsetPx.snapTo(-offscreen)
    pullOffsetPx.animateTo(0f, tween(180))
}
