package com.voxapps.notes.domain.debug

import com.voxapps.notes.data.NotesRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Debug-only helper (see the `BuildConfig.DEBUG` gate at the call site in `GeneralSettingsTab`)
 * that inserts a batch of sample notes spanning the last ~3 months, spread unevenly across days
 * (including some empty days and some multi-note days) so the calendar view has something visually
 * meaningful to page through and verify. Never called from a production code path — manual trigger
 * only, no auto-seeding on launch.
 */
object DebugDataSeeder {
    private val SAMPLE_TITLES = listOf("Groceries", "Meeting notes", "Idea", "Reminder", "Todo", null)
    private val SAMPLE_TEXTS = listOf(
        "Buy milk, eggs, bread", "Discuss Q3 roadmap", "Try the new recipe", "Call the dentist",
        "Finish the report", "Water the plants", "Read chapter 4"
    )

    suspend fun seed(notesRepo: NotesRepository, categoryId: Long? = null, monthsBack: Int = 3) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val random = Random(System.currentTimeMillis())
        repeat(60) {
            val daysBack = random.nextInt(monthsBack * 30)
            val date = today.minusDays(daysBack.toLong())
            val millis = date.atStartOfDay(zone).toInstant().toEpochMilli() +
                random.nextLong(0, 86_400_000L)
            notesRepo.addNote(
                title = SAMPLE_TITLES.random(random),
                text = SAMPLE_TEXTS.random(random),
                categoryId = categoryId,
                createdAt = millis
            )
        }
    }
}
