package com.voxapps.expenses.domain.limits

import com.voxapps.expenses.data.SpendingLimit
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * Pure date-window math for [SpendingLimit.period] — no Android deps, unit-testable. A "period" is a
 * rolling calendar window (the current ISO week or calendar month) used both to sum matching expenses
 * and as a stable key so [com.voxapps.expenses.domain.limits.SpendingLimitAlertRepository] only alerts
 * once per window, naturally resetting when the window rolls over.
 */
object SpendingPeriod {
    /** Start of the window containing [today] (inclusive), as an epoch-millis timestamp at midnight
     *  in the device's default time zone. */
    fun windowStartMillis(period: String, today: LocalDate = LocalDate.now()): Long {
        val start = when (period) {
            SpendingLimit.PERIOD_WEEKLY -> today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            SpendingLimit.PERIOD_MONTHLY -> today.withDayOfMonth(1)
            else -> today
        }
        return start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** A string that's stable for the whole window and changes when it rolls over, e.g.
     *  "WEEKLY:2026-W29" or "MONTHLY:2026-7". */
    fun periodKey(period: String, today: LocalDate = LocalDate.now()): String = when (period) {
        SpendingLimit.PERIOD_WEEKLY -> {
            val week = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val weekYear = today.get(IsoFields.WEEK_BASED_YEAR)
            "${SpendingLimit.PERIOD_WEEKLY}:$weekYear-W$week"
        }
        SpendingLimit.PERIOD_MONTHLY -> "${SpendingLimit.PERIOD_MONTHLY}:${today.year}-${today.monthValue}"
        else -> "$period:${today}"
    }
}
