package com.voxapps.textmatch.extract

import java.time.LocalDate
import java.time.LocalTime

/**
 * Finds every date and time present in free text, in document order.
 *
 * Reports all of them rather than picking one, and rules on none of them. Whether a date may lie in
 * the future, whether the first or the last is the one that matters, whether a time without a date
 * means anything at all — those answers differ per caller and are not knowable here.
 */
object DateTimeExtractor {

    /**
     * Day-first and year-first orderings, both with any of the common separators. Day-first is
     * tried before month-first because the ambiguous overlap (both components below 13) resolves to
     * the same calendar date far more often than not outside US-formatted documents; a caller that
     * knows its documents are month-first should read [DateFinding.raw] and reinterpret.
     */
    private val dayFirst = Regex("""\b(\d{1,2})[./-](\d{1,2})[./-](\d{4}|\d{2})\b""")
    private val yearFirst = Regex("""\b(\d{4})[./-](\d{1,2})[./-](\d{1,2})\b""")

    /** 24-hour clock, optional seconds. Deliberately not matching a bare "9h" or "9 PM": those need
     *  locale knowledge this package does not have. */
    private val timeRegex = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?\b""")

    fun findDates(text: String): List<DateFinding> {
        val out = mutableListOf<DateFinding>()
        text.lineSequence().forEachIndexed { index, line ->
            yearFirst.findAll(line).forEach { m ->
                val (y, mo, d) = m.destructured
                dateOrNull(y.toInt(), mo.toInt(), d.toInt())?.let {
                    out += DateFinding(it, m.value, index)
                }
            }
            dayFirst.findAll(line).forEach { m ->
                val (d, mo, y) = m.destructured
                dateOrNull(expandYear(y.toInt()), mo.toInt(), d.toInt())?.let {
                    out += DateFinding(it, m.value, index)
                }
            }
        }
        return out
    }

    fun findTimes(text: String): List<TimeFinding> {
        val out = mutableListOf<TimeFinding>()
        text.lineSequence().forEachIndexed { index, line ->
            timeRegex.findAll(line).forEach { m ->
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                val sec = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                runCatching { LocalTime.of(h, min, sec) }.getOrNull()?.let {
                    out += TimeFinding(it, m.value, index)
                }
            }
        }
        return out
    }

    /** A two-digit year is read into the current century, which is the convention documents use. */
    private fun expandYear(year: Int): Int = if (year < 100) 2000 + year else year

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()
}
