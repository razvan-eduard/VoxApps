package com.voxapps.calendarapp.ui

/** Time-of-day emoji pair bracketing the "nothing else today" label — shared by the day view's hour
 *  grid, the to-do timeline, and the home-screen widget, so the three stay in sync. [hour] is 0-23
 *  (e.g. `java.time.LocalTime.now().hour`). A small mood touch, not a strict day-period taxonomy. */
fun nothingElseTodayEmojis(hour: Int): Pair<String, String> = when (hour) {
    in 5..16 -> "☕" to "☀️"
    in 17..20 -> "🍵" to "🌅"
    else -> "🌙" to "✨"
}
