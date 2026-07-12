package com.voxapps.expenses.receiver

import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.state.SessionManager
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vox Hub's export/import for this app, extracted from the BroadcastReceiver so it's unit-testable
 * without Android (mirrors [ExpensesReadResponder] / vox-notes' NotesExportImportHandler). Respects
 * the same biometric-lock gate as reads — an export/import request while the app is locked never
 * touches the DB.
 */
class ExpensesExportImportHandler(
    private val settingsRepo: ExpensesSettingsRepository,
    private val sessionManager: SessionManager,
    private val expensesRepo: ExpensesRepository
) {
    suspend fun export(scope: String = VoxIpc.EXPORT_SCOPE_BOTH): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = ExpensesReadResponder.LOCKED_MESSAGE)

        val json = JSONObject()
        if (scope != VoxIpc.EXPORT_SCOPE_DATA) {
            json.put("settings", settings.toJson())
        }
        if (scope != VoxIpc.EXPORT_SCOPE_SETTINGS) {
            val categories = expensesRepo.categories.first()
            val spendingLimits = expensesRepo.spendingLimits.first()
            val expensesWithDetails = expensesRepo.expensesWithDetails.first()
            json.put("categories", JSONArray(categories.map { it.toJson() }))
            json.put("spendingLimits", JSONArray(spendingLimits.map { it.toJson() }))
            json.put("expenses", JSONArray(expensesWithDetails.map { it.expense.toJson(it.items) }))
        }
        return VoxResult(ok = true, text = json.toString())
    }

    suspend fun import(payloadJson: String): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = ExpensesReadResponder.LOCKED_MESSAGE)

        val root = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid import payload")
        }

        root.optJSONObject("settings")?.let { settingsRepo.restoreSettings(it.toExpensesSettings()) }

        val existingCategories = expensesRepo.categories.first()
        val nameToId = existingCategories.associate { it.name.lowercase() to it.id }.toMutableMap()
        val importedIdToLocalId = mutableMapOf<Long, Long?>()
        val importedCategories = root.optJSONArray("categories") ?: JSONArray()
        var categoriesCreated = 0
        for (i in 0 until importedCategories.length()) {
            val c = importedCategories.getJSONObject(i)
            val name = c.optString("name").trim()
            if (name.isEmpty()) continue
            val importedId = c.optLong("id")
            val localId = nameToId[name.lowercase()] ?: run {
                val newId = expensesRepo.addCategory(
                    name,
                    c.optLong("colorArgb"),
                    c.optInt("position"),
                    c.optLong("createdAt", System.currentTimeMillis())
                )
                if (newId > 0) {
                    categoriesCreated++
                    nameToId[name.lowercase()] = newId
                }
                newId.takeIf { it > 0 }
            }
            importedIdToLocalId[importedId] = localId
        }

        // Replace, not merge (mirrors vox-notes' NotesExportImportHandler): importing a data
        // payload restores that snapshot rather than merging with what's already on this device.
        // Snapshot pre-existing spending limits/expenses, insert every imported one, then delete
        // exactly what existed before. Categories are untouched here — merged by name above.
        if (root.has("spendingLimits")) {
            val preExistingLimits = expensesRepo.spendingLimits.first()

            val importedLimits = root.optJSONArray("spendingLimits") ?: JSONArray()
            for (i in 0 until importedLimits.length()) {
                val l = importedLimits.getJSONObject(i)
                val importedCategoryId = if (l.has("categoryId") && !l.isNull("categoryId")) l.optLong("categoryId") else null
                val categoryId = importedCategoryId?.let { importedIdToLocalId[it] }
                expensesRepo.addSpendingLimit(categoryId, l.optDouble("amountHomeCurrency"), l.optString("period"))
            }

            preExistingLimits.forEach { expensesRepo.deleteSpendingLimit(it) }
        }

        var expensesCreated = 0
        if (root.has("expenses")) {
            val preExistingExpenseIds = expensesRepo.expensesSnapshot().map { it.id }

            val importedExpenses = root.optJSONArray("expenses") ?: JSONArray()
            for (i in 0 until importedExpenses.length()) {
                val e = importedExpenses.getJSONObject(i)
                val importedCategoryId = if (e.has("categoryId") && !e.isNull("categoryId")) e.optLong("categoryId") else null
                val categoryId = importedCategoryId?.let { importedIdToLocalId[it] }
                val items = (e.optJSONArray("items") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { idx ->
                        val it = arr.getJSONObject(idx)
                        ExpenseLineItem(
                            expenseId = 0,
                            name = it.optString("name"),
                            quantity = it.optDouble("quantity", 1.0),
                            unitPrice = it.optDouble("unitPrice", 0.0),
                            position = it.optInt("position", idx),
                            netAmount = it.optDoubleOrNull("netAmount"),
                            vatAmount = it.optDoubleOrNull("vatAmount"),
                            grossAmount = it.optDoubleOrNull("grossAmount")
                        )
                    }
                }
                expensesRepo.addExpense(
                    title = e.optStringOrNull("title"),
                    totalAmount = e.optDouble("totalAmount"),
                    currencyCode = e.optString("currencyCode"),
                    vendor = e.optStringOrNull("vendor"),
                    bank = e.optStringOrNull("bank"),
                    location = e.optStringOrNull("location"),
                    dateTime = e.optLong("dateTime", System.currentTimeMillis()),
                    comments = e.optStringOrNull("comments"),
                    categoryId = categoryId,
                    items = items
                )
                expensesCreated++
            }

            preExistingExpenseIds.forEach { expensesRepo.deleteExpenseById(it) }
        }

        return VoxResult(
            ok = true,
            text = "$expensesCreated expenses imported, $categoriesCreated new categories " +
                "(${importedCategories.length() - categoriesCreated} matched existing)"
        )
    }
}

private fun ExpensesSettings.toJson(): JSONObject = JSONObject().apply {
    put("isBiometricRequired", isBiometricRequired)
    put("sessionTimeoutMinutes", sessionTimeoutMinutes)
    put("language", language)
    put("defaultCurrency", defaultCurrency)
    put("defaultVoiceCategoryId", defaultVoiceCategoryId)
    put("voiceSaveToastEnabled", voiceSaveToastEnabled)
    put("autoCreateVoiceCategory", autoCreateVoiceCategory)
    put("scheduledMergeInterval", scheduledMergeInterval)
    put("scheduledExpenseDedupInterval", scheduledExpenseDedupInterval)
    put("homeCurrency", homeCurrency)
    put("paymentSourcePackages", JSONArray(paymentSourcePackages.toList()))
    put("bankingSourcePackages", JSONArray(bankingSourcePackages.toList()))
    put("debugLoggingEnabled", debugLoggingEnabled)
    put("vatDisplayEnabled", vatDisplayEnabled)
    put("decimalSeparator", decimalSeparator)
    put("calendarViewEnabled", calendarViewEnabled)
    // appCacheJson intentionally excluded — internal cache, not user data.
}

private fun JSONObject.toExpensesSettings(): ExpensesSettings {
    val packagesArray = optJSONArray("paymentSourcePackages")
    val packages = if (packagesArray != null) {
        (0 until packagesArray.length()).map { packagesArray.optString(it) }.toSet()
    } else {
        emptySet()
    }
    val bankingArray = optJSONArray("bankingSourcePackages")
    val bankingPackages = if (bankingArray != null) {
        (0 until bankingArray.length()).map { bankingArray.optString(it) }.toSet()
    } else {
        emptySet()
    }
    return ExpensesSettings(
        isBiometricRequired = optBoolean("isBiometricRequired", false),
        sessionTimeoutMinutes = optInt("sessionTimeoutMinutes", ExpensesSettings.TIMEOUT_30M),
        language = optString("language", ExpensesSettings.DEFAULT_LANGUAGE),
        defaultCurrency = optString("defaultCurrency", ExpensesSettings.DEFAULT_CURRENCY),
        defaultVoiceCategoryId = if (has("defaultVoiceCategoryId") && !isNull("defaultVoiceCategoryId")) optLong("defaultVoiceCategoryId") else null,
        voiceSaveToastEnabled = optBoolean("voiceSaveToastEnabled", false),
        autoCreateVoiceCategory = optBoolean("autoCreateVoiceCategory", false),
        scheduledMergeInterval = optString("scheduledMergeInterval", ExpensesSettings.INTERVAL_OFF),
        scheduledExpenseDedupInterval = optString("scheduledExpenseDedupInterval", ExpensesSettings.INTERVAL_OFF),
        homeCurrency = optString("homeCurrency", ExpensesSettings.DEFAULT_CURRENCY),
        paymentSourcePackages = packages,
        bankingSourcePackages = bankingPackages,
        debugLoggingEnabled = optBoolean("debugLoggingEnabled", false),
        vatDisplayEnabled = optBoolean("vatDisplayEnabled", false),
        decimalSeparator = optString("decimalSeparator", ExpensesSettings.DECIMAL_PERIOD),
        calendarViewEnabled = optBoolean("calendarViewEnabled", false)
    )
}

private fun Category.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("position", position)
    put("createdAt", createdAt)
}

private fun SpendingLimit.toJson(): JSONObject = JSONObject().apply {
    put("categoryId", categoryId)
    put("amountHomeCurrency", amountHomeCurrency)
    put("period", period)
}

private fun Expense.toJson(items: List<ExpenseLineItem>): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("totalAmount", totalAmount)
    put("currencyCode", currencyCode)
    put("vendor", vendor)
    put("bank", bank)
    put("location", location)
    put("dateTime", dateTime)
    put("comments", comments)
    put("categoryId", categoryId)
    put("items", JSONArray(items.map { it.toJson() }))
}

private fun ExpenseLineItem.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("quantity", quantity)
    put("unitPrice", unitPrice)
    put("position", position)
    put("netAmount", netAmount)
    put("vatAmount", vatAmount)
    put("grossAmount", grossAmount)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
