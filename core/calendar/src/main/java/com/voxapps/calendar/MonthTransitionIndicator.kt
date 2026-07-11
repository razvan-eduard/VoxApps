package com.voxapps.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * A centered label showing the month the user is swiping TOWARD, invisible at rest
 * ([PagerState.currentPageOffsetFraction] == 0) and fading to fully opaque as the drag progresses.
 * Driven directly off the live gesture fraction rather than a separate animation, so it always
 * tracks the actual swipe rather than a fixed-duration animation that could drift out of sync.
 */
@Composable
internal fun MonthTransitionIndicator(
    pagerState: PagerState,
    monthForPage: (Int) -> YearMonth,
    modifier: Modifier = Modifier
) {
    val offsetFraction = pagerState.currentPageOffsetFraction
    val fadeAlpha = (abs(offsetFraction) * 2.5f).coerceIn(0f, 1f)
    if (fadeAlpha <= 0.01f) return

    val targetPage = if (offsetFraction >= 0f) pagerState.currentPage + 1 else pagerState.currentPage - 1
    val targetMonth = monthForPage(targetPage)
    val locale = Locale.getDefault()

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.graphicsLayer { alpha = fadeAlpha }
        ) {
            Text(
                text = "${targetMonth.month.getDisplayName(TextStyle.FULL, locale)} ${targetMonth.year}",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
            )
        }
    }
}
