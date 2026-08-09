package com.voxapps.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Where "now" falls, drawn the same way everywhere it appears.
 *
 * A dot on whatever axis the surrounding rows are aligned to, a rule across the rest of the width,
 * and the time at the end. It existed three times — the day grid, the week grid and the to-do
 * timeline — with the same dot size, the same error colour and the same short-time format written
 * out separately, which is how two of them ended up a minute behind the third.
 *
 * [leadingWidth] is the gutter each surface has before its content starts: the hour labels in a
 * grid, the "up next" column in a timeline. [nowMillis] is passed in rather than read here, so the
 * caller's ticking clock governs every part of its screen at once.
 */
@Composable
fun NowLine(
    nowMillis: Long,
    modifier: Modifier = Modifier,
    leadingWidth: Dp = 0.dp,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.error,
    thickness: Dp = 2.dp,
    dotSize: Dp = 8.dp
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (leadingWidth > 0.dp) Spacer(Modifier.width(leadingWidth))
        Box(modifier = Modifier.size(dotSize).background(color, CircleShape))
        Box(modifier = Modifier.weight(1f).height(thickness).background(color))
        Text(
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nowMillis)),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
            modifier = Modifier.padding(start = 6.dp, end = 4.dp)
        )
    }
}
