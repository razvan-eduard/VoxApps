package com.voxapps.expenses.receiver

import com.voxapps.datahygiene.SyncIdentity
import com.voxapps.datahygiene.planMerge
import com.voxapps.expenses.data.CategoryPalette
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.llm.optTransactionDirection
import com.voxapps.expenses.domain.llm.toJsonValue
import com.voxapps.expenses.state.SessionManager
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vox Hub's peer-to-peer sync for this app (see [VoxIpc.OP_SYNC_EXPORT]/[VoxIpc.OP_SYNC_MERGE]) —
 * deliberately separate from [ExpensesExportImportHandler], which is a one-directional *restore*
 * (wipe pre-existing rows, insert a full snapshot verbatim). This is a *delta* merge: only entries
 * changed since a watermark, reconciled via [com.voxapps.datahygiene.planMerge]'s insert-if-new /
 * last-write-wins / delete-on-tombstone algorithm, never a blind overwrite.
 *
 * Categories travel by name, not id (a local Room sequence has no meaning on another phone) — same
 * convention [ExpensesExportImportHandler] already uses. Line items travel *with* their parent
 * expense rather than getting their own sync identity — they're never edited or addressed
 * independently of the expense they belong to (the in-app edit flow already replaces an expense's
 * entire line-item list atomically, see [ExpensesRepository.updateExpense]), so whichever version of
 * the expense wins the last-write-wins comparison carries its line items along with it. No separate
 * per-item merge pass, no per-item uid — consistent with [Expense] itself already being whole-record
 * last-write-wins, not field-level merge.
 */
class ExpensesSyncHandler(
    private val settingsRepo: ExpensesSettingsRepository,
    private val sessionManager: SessionManager,
    private val expensesRepo: ExpensesRepository
) {
    suspend fun export(since: Long, scopeNames: List<String>?): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = ExpensesReadResponder.LOCKED_MESSAGE)

        val scopeSet = scopeNames?.takeIf { it.isNotEmpty() }?.map { it.lowercase() }?.toSet()

        // expensesWithDetails (the same joined query ExpensesExportImportHandler's OP_EXPORT path
        // uses) resolves each expense's category and line items in one query — no separate
        // categoryNameById map to build, and line items ride along for free.
        val changed = expensesRepo.expensesWithDetails.first()
            .filter { it.expense.updatedAt > since }
            .filter { details ->
                if (scopeSet == null) return@filter true
                val name = details.category?.name ?: return@filter false
                name.lowercase() in scopeSet
            }
        val tombstones = expensesRepo.tombstonesSince(since)

        val json = JSONObject()
        json.put("entries", JSONArray(changed.map { it.expense.toSyncJson(it.category?.name, it.items) }))
        json.put("tombstones", JSONArray(tombstones.map { JSONObject().put("uid", it.uid).put("deletedAt", it.deletedAt) }))
        return VoxResult(ok = true, text = json.toString())
    }

    suspend fun merge(deltaJson: String): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = ExpensesReadResponder.LOCKED_MESSAGE)

        val root = try {
            JSONObject(deltaJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid sync payload")
        }

        // Same auto-create-by-name convention ExpensesExportImportHandler.import() already uses.
        val existingCategories = expensesRepo.categories.first().toMutableList()
        val nameToId = existingCategories.associate { it.name.lowercase() to it.id }.toMutableMap()
        // Fetched once per merge, not per-entry — see CategoryPalette.unusedOrRandomColor's
        // precedingColor param.
        val precedingColor = expensesRepo.mostRecentCategoryColor()

        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        val remoteEntries = (0 until entriesJson.length()).map { i ->
            val e = entriesJson.getJSONObject(i)
            val categoryName = e.optNullableString("categoryName")
            val categoryId = categoryName?.let { name ->
                nameToId[name.lowercase()] ?: run {
                    val newId = expensesRepo.addCategory(
                        name,
                        CategoryPalette.unusedOrRandomColor(existingCategories.map { it.colorArgb }, precedingColor),
                        existingCategories.size,
                        System.currentTimeMillis()
                    )
                    if (newId > 0) nameToId[name.lowercase()] = newId
                    newId.takeIf { it > 0 }
                }
            }
            e.toExpense(categoryId) to e.toLineItems()
        }
        val tombstonesJson = root.optJSONArray("tombstones") ?: JSONArray()
        val remoteTombstoneUids = (0 until tombstonesJson.length())
            .map { tombstonesJson.getJSONObject(it).optString("uid") }
            .toSet()

        val local = expensesRepo.expensesSnapshot()
        val plan = ExpenseSyncIdentity.planMerge(local, remoteEntries.map { it.first }, remoteTombstoneUids)
        val itemsByUid = remoteEntries.associate { (expense, items) -> expense.uid to items }

        for (expense in plan.toInsert) expensesRepo.insertSyncedExpense(expense, itemsByUid[expense.uid].orEmpty())
        for (expense in plan.toUpdate) {
            val localId = expensesRepo.getIdByUid(expense.uid) ?: continue
            expensesRepo.updateSyncedExpense(expense.copy(id = localId), itemsByUid[expense.uid].orEmpty())
        }
        for (uid in plan.toDeleteUids) expensesRepo.deleteExpenseByUid(uid)

        return VoxResult(
            ok = true,
            text = "${plan.toInsert.size} inserted, ${plan.toUpdate.size} updated, ${plan.toDeleteUids.size} deleted"
        )
    }
}

private object ExpenseSyncIdentity : SyncIdentity<Expense> {
    override fun uidOf(record: Expense): String = record.uid
    override fun updatedAtOf(record: Expense): Long = record.updatedAt
}

private fun Expense.toSyncJson(categoryName: String?, items: List<ExpenseLineItem>): JSONObject = JSONObject().apply {
    put("uid", uid)
    put("title", title)
    put("totalAmount", totalAmount)
    put("currencyCode", currencyCode)
    put("vendor", vendor)
    put("bank", bank)
    put("location", location)
    put("dateTime", dateTime)
    put("comments", comments)
    put("categoryName", categoryName)
    put("direction", direction.toJsonValue())
    put("receiptImageName", receiptImageName)
    put("isStub", isStub)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("lineItems", JSONArray(items.sortedBy { it.position }.map { it.toSyncJson() }))
}

/** No uid/updatedAt of its own — see [ExpensesSyncHandler]'s doc comment for why line items don't
 *  need independent sync identity. */
private fun ExpenseLineItem.toSyncJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("quantity", quantity)
    put("unitPrice", unitPrice)
    put("position", position)
    netAmount?.let { put("netAmount", it) }
    vatAmount?.let { put("vatAmount", it) }
    grossAmount?.let { put("grossAmount", it) }
}

/** [expenseId] is set later by the repository once it knows which local row (new insert or
 *  resolved-by-uid update) these items belong to — see [ExpensesRepository.insertSyncedExpense]/
 *  [ExpensesRepository.updateSyncedExpense]. */
private fun JSONObject.toLineItems(): List<ExpenseLineItem> {
    val array = optJSONArray("lineItems") ?: return emptyList()
    return (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        ExpenseLineItem(
            expenseId = 0,
            name = item.optString("name"),
            quantity = item.optDouble("quantity"),
            unitPrice = item.optDouble("unitPrice"),
            position = item.optInt("position"),
            netAmount = if (item.has("netAmount") && !item.isNull("netAmount")) item.optDouble("netAmount") else null,
            vatAmount = if (item.has("vatAmount") && !item.isNull("vatAmount")) item.optDouble("vatAmount") else null,
            grossAmount = if (item.has("grossAmount") && !item.isNull("grossAmount")) item.optDouble("grossAmount") else null
        )
    }
}

private fun JSONObject.toExpense(categoryId: Long?): Expense = Expense(
    uid = optString("uid"),
    title = optNullableString("title"),
    totalAmount = optDouble("totalAmount"),
    currencyCode = optString("currencyCode"),
    vendor = optNullableString("vendor"),
    bank = optNullableString("bank"),
    location = optNullableString("location"),
    dateTime = optLong("dateTime"),
    comments = optNullableString("comments"),
    categoryId = categoryId,
    direction = optTransactionDirection(),
    receiptImageName = optNullableString("receiptImageName"),
    isStub = optBoolean("isStub", false),
    createdAt = optLong("createdAt"),
    updatedAt = optLong("updatedAt")
)

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
