package com.voxapps.expenses.domain.debug

import com.voxapps.expenses.data.ExpensesRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Debug-only helper (see the `BuildConfig.DEBUG` gate at the call site in `GeneralSettingsTab`)
 * that inserts a batch of sample expenses spanning the last ~3 months, spread unevenly across days
 * so the calendar view has something visually meaningful to page through. Also cycles through a
 * small fixed sample bank/vendor list so the bank/vendor filter dropdowns have real options to
 * exercise. Never called from a production code path — manual trigger only, no auto-seeding on
 * launch.
 */
object DebugDataSeeder {
    private val SAMPLE_TITLES = listOf("Groceries", "Coffee", "Fuel", "Pharmacy", "Dinner", "Subscription", null)
    private val SAMPLE_VENDORS = listOf("Kaufland", "Lidl", "Mega Image", "OMV", "Dr.Max", "Netflix", "Carrefour")
    private val SAMPLE_BANKS = listOf("ING BANK", "BCR", "Raiffeisen", "BRD", "Revolut")

    suspend fun seed(expensesRepo: ExpensesRepository, currencyCode: String, monthsBack: Int = 3) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val random = Random(System.currentTimeMillis())
        repeat(60) {
            val daysBack = random.nextInt(monthsBack * 30)
            val date = today.minusDays(daysBack.toLong())
            val millis = date.atStartOfDay(zone).toInstant().toEpochMilli() +
                random.nextLong(0, 86_400_000L)
            expensesRepo.addExpense(
                title = SAMPLE_TITLES.random(random),
                totalAmount = random.nextDouble(5.0, 400.0),
                currencyCode = currencyCode,
                vendor = SAMPLE_VENDORS.random(random),
                location = null,
                dateTime = millis,
                comments = null,
                categoryId = null,
                items = emptyList()
            )
        }
    }
}
