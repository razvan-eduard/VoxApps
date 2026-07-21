package com.voxapps.expenses.receiver

import com.voxapps.datahygiene.SyncIdentity
import com.voxapps.datahygiene.planMerge
import com.voxapps.expenses.data.CategoryPalette
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
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
 * convention [ExpensesExportImportHandler] already uses. Line items are deliberately NOT included in
 * the sync payload for v1: they have no sync identity of their own yet, so an itemized receipt syncs
 * as just its header fields (title/vendor/total/etc.) — a known, accepted limitation, not a bug.
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

        val categoryNameById = expensesRepo.categories.first().associate { it.id to it.name }
        val scopeSet = scopeNames?.takeIf { it.isNotEmpty() }?.map { it.lowercase() }?.toSet()

        val changed = expensesRepo.expensesSnapshot()
            .filter { it.updatedAt > since }
            .filter { expense ->
                if (scopeSet == null) return@filter true
                val name = expense.categoryId?.let { categoryNameById[it] } ?: return@filter false
                name.lowercase() in scopeSet
            }
        val tombstones = expensesRepo.tombstonesSince(since)

        val json = JSONObject()
        json.put("entries", JSONArray(changed.map { it.toSyncJson(it.categoryId?.let { id -> categoryNameById[id] }) }))
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

        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        val remoteEntries = (0 until entriesJson.length()).map { i ->
            val e = entriesJson.getJSONObject(i)
            val categoryName = e.optNullableString("categoryName")
            val categoryId = categoryName?.let { name ->
                nameToId[name.lowercase()] ?: run {
                    val newId = expensesRepo.addCategory(
                        name,
                        CategoryPalette.unusedOrRandomColor(existingCategories.map { it.colorArgb }),
                        existingCategories.size,
                        System.currentTimeMillis()
                    )
                    if (newId > 0) nameToId[name.lowercase()] = newId
                    newId.takeIf { it > 0 }
                }
            }
            e.toExpense(categoryId)
        }
        val tombstonesJson = root.optJSONArray("tombstones") ?: JSONArray()
        val remoteTombstoneUids = (0 until tombstonesJson.length())
            .map { tombstonesJson.getJSONObject(it).optString("uid") }
            .toSet()

        val local = expensesRepo.expensesSnapshot()
        val plan = ExpenseSyncIdentity.planMerge(local, remoteEntries, remoteTombstoneUids)

        for (expense in plan.toInsert) expensesRepo.insertSyncedExpense(expense)
        for (expense in plan.toUpdate) {
            val localId = expensesRepo.getIdByUid(expense.uid) ?: continue
            expensesRepo.updateSyncedExpense(expense.copy(id = localId))
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

private fun Expense.toSyncJson(categoryName: String?): JSONObject = JSONObject().apply {
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
    put("receiptImageName", receiptImageName)
    put("isStub", isStub)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
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
    receiptImageName = optNullableString("receiptImageName"),
    isStub = optBoolean("isStub", false),
    createdAt = optLong("createdAt"),
    updatedAt = optLong("updatedAt")
)

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
