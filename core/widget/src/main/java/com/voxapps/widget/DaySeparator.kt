package com.voxapps.widget

import androidx.compose.runtime.Composable
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a day separator marks out today. */
enum class DaySeparatorStyle {
    /** Today gets a filled, rounded background — reads as a badge on a list of cards. */
    Pill,

    /** Today is only bolder and slightly larger. For a widget where a filled badge would read as a
     *  button rather than a heading. */
    Plain
}

/**
 * The day heading between groups of rows in a home-screen widget.
 *
 * Three widgets drew this, and two of them were byte-identical while the third differed in exactly
 * one decision: whether today gets a pill. That decision is the parameter; everything else — the
 * centring, the weights, the sizes, the theme colour — was never app-specific and is written here
 * once.
 *
 * The *text* is not shared, deliberately. Each widget says something different about today ("Today",
 * "Today, 9 Aug", "Up next (Today, 9 Aug)") because each is heading a different kind of list, and
 * folding that into a parameterised sentence would take three call sites' worth of wording into one
 * place that has no idea what any of them are listing. [WidgetDayFormats] shares the date patterns
 * they build those sentences from.
 *
 * This is a Glance composable: a widget cannot use the app's Compose UI day header, since the two
 * are different composition worlds despite the identical-looking code.
 */
@Composable
fun DaySeparatorLabel(
    text: String,
    isToday: Boolean,
    style: DaySeparatorStyle = DaySeparatorStyle.Pill,
    modifier: GlanceModifier = GlanceModifier
) {
    val label: @Composable () -> Unit = {
        Text(
            text = text,
            style = TextStyle(
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (isToday) 13.sp else 12.sp,
                color = if (isToday && style == DaySeparatorStyle.Pill) GlanceTheme.colors.onPrimary
                else GlanceTheme.colors.primary
            )
        )
    }

    Box(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isToday && style == DaySeparatorStyle.Pill) {
            Box(
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(16.dp)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
                content = { label() }
            )
        } else {
            label()
        }
    }
}

/** The two date patterns every widget's day heading is built from. */
object WidgetDayFormats {

    /** `9 Aug` — the tail of a "today"/"tomorrow" sentence. */
    fun short(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern("d MMM", locale))

    /** `Sat, 9 Aug` — a day that needs naming because it is neither today nor tomorrow. */
    fun weekday(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
}
