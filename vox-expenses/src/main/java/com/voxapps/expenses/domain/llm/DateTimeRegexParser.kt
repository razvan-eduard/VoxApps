package com.voxapps.expenses.domain.llm

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Fast, deterministic regex extraction of date and time from raw OCR text.
 * Used to offload search effort from the LLM, reducing latency and cost.
 */
object DateTimeRegexParser {

    /**
     * Common receipt date patterns:
     * 1. DD.MM.YYYY (Romanian/EU)
     * 2. DD/MM/YYYY
     * 3. YYYY-MM-DD (ISO)
     * 4. DD-MM-YYYY
     */
    private val dateRegexes = listOf(
        Regex("""(\d{2})[./-](\d{2})[./-](\d{4})"""), // DD.MM.YYYY, DD/MM/YYYY, DD-MM-YYYY
        Regex("""(\d{4})-(\d{2})-(\d{2})""")        // YYYY-MM-DD
    )

    /**
     * Common receipt time patterns:
     * 1. HH:mm (24h)
     * 2. HH:mm:ss
     */
    private val timeRegex = Regex("""([01]\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?""")

    data class Result(val date: String?, val time: String?)

    fun parse(text: String): Result {
        var foundDate: String? = null
        var foundTime: String? = null
        val today = LocalDate.now()
        val now = LocalTime.now()

        // Pass 1: Extract Date
        for (regex in dateRegexes) {
            val match = regex.find(text) ?: continue
            val g = match.groupValues
            foundDate = try {
                val candidate = if (g[3].length == 4) { // DD.MM.YYYY format
                    val d = g[1].toInt()
                    val m = g[2].toInt()
                    val y = g[3].toInt()
                    LocalDate.of(y, m, d)
                } else { // YYYY-MM-DD format
                    val y = g[1].toInt()
                    val m = g[2].toInt()
                    val d = g[3].toInt()
                    LocalDate.of(y, m, d)
                }
                
                // Sanity check: Date cannot be in the future
                if (candidate.isAfter(today)) null 
                else candidate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                null
            }
            if (foundDate != null) break
        }

        // Pass 2: Extract Time
        val timeMatch = timeRegex.find(text)
        if (timeMatch != null) {
            val h = timeMatch.groupValues[1].toInt()
            val m = timeMatch.groupValues[2].toInt()
            foundTime = try {
                val candidate = LocalTime.of(h, m)
                
                // Sanity check: If the date is today, the time cannot be in the future
                if (foundDate == today.format(DateTimeFormatter.ISO_LOCAL_DATE) && candidate.isAfter(now)) {
                    null
                } else {
                    candidate.format(DateTimeFormatter.ofPattern("HH:mm"))
                }
            } catch (e: Exception) {
                null
            }
        }

        return Result(foundDate, foundTime)
    }
}
