package com.voxapps.expenses.domain.llm

import com.voxapps.textmatch.extract.DateTimeExtractor
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Expenses' reading of when a document was issued. Finding the candidates belongs to
 * [DateTimeExtractor], which reports every date and time present and rules on none of them; what
 * remains here is the rule that is an expense decision rather than a fact about text.
 *
 * That rule is that neither may lie in the future. A receipt records something that already
 * happened, so a later date is a misread rather than a document from tomorrow — the opposite of
 * what the same extraction means to a calendar, which is why the check cannot live alongside the
 * patterns.
 */
object DateTimeRegexParser {

    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val hourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    data class Result(val date: String?, val time: String?)

    fun parse(text: String): Result {
        val today = LocalDate.now()
        val now = LocalTime.now()

        val date = DateTimeExtractor.findDates(text)
            .map { it.value }
            .firstOrNull { !it.isAfter(today) }

        val time = DateTimeExtractor.findTimes(text)
            .map { it.value }
            .firstOrNull { candidate ->
                // A time is only checked against the clock when the document is from today; on any
                // earlier day every time of day has already passed.
                date != today || !candidate.isAfter(now)
            }

        return Result(date?.format(isoDate), time?.format(hourMinute))
    }
}
