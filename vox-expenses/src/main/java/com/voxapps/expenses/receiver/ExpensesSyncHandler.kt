package com.voxapps.expenses.receiver

import com.voxapps.datahygiene.SyncDeltaKeys
import com.voxapps.datahygiene.SyncIdentity
import com.voxapps.datahygiene.SyncLevel
import com.voxapps.datahygiene.SyncPaging
import com.voxapps.datahygiene.planMerge
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.accounts.BankAccountTree
import com.voxapps.expenses.domain.llm.optTransactionDirection
import com.voxapps.expenses.domain.llm.toJsonValue
import com.voxapps.expenses.state.SessionManager
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import com.voxapps.design.color.VoxColorPalette

/**
 * Vox Hub's peer-to-peer sync for this app (see [VoxIpc.OP_SYNC_EXPORT]/[VoxIpc.OP_SYNC_MERGE]) —
 * deliberately separate from [ExpensesExportImportHandler], which is a one-directional *restore*.
 * This is a *delta* merge: pages of entries changed since a watermark, reconciled via
 * [com.voxapps.datahygiene.planMerge]'s insert-if-new / last-write-wins / delete-on-tombstone
 * algorithm, never a blind overwrite.
 *
 * What the export volunteers is governed by [ExpensesSettings.syncLevel] plus the per-peer account
 * scope the command carries (see [SyncLevel] for the three rungs); records the user explicitly
 * pushed ([VoxCommand.uids]) travel at every rung. Deltas are paged ([SyncPaging]) so a large first
 * sync crosses the binder boundary in bounded pieces.
 *
 * On the wire, every field the entity has travels, and links travel by NAME (category, bank
 * account, recipient — a local Room id has no meaning on another phone). A key holding an explicit
 * JSON null means "this field IS null" and overwrites; an ABSENT key means the sending build never
 * knew the field, and the merge keeps the local row's value — so an older peer's narrower delta
 * can't blank fields it never heard of. Line items travel *with* their parent expense (no sync
 * identity of their own — the in-app edit flow already replaces an expense's entire line-item list
 * atomically, so whichever version wins last-write-wins carries its items along).
 *
 * Rows a merge INSERTS are stamped with the sending device's identity
 * ([VoxCommand.sourceDeviceId]/[VoxCommand.sourceDeviceName]) as their provenance; an update never
 * rewrites an existing row's stamp — where a record came from doesn't change when it's edited.
 */
class ExpensesSyncHandler(
    private val settingsRepo: ExpensesSettingsRepository,
    private val sessionManager: SessionManager,
    private val expensesRepo: ExpensesRepository,
    private val lockedMessage: String
) {
    suspend fun export(command: VoxCommand): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = lockedMessage)

        val level = ExpensesSettings.syncLevelOf(settings.syncLevel)
        val since = command.since ?: 0L
        // null = everything, empty = nothing — the wire contract; see VoxCommand.scopeNames.
        val scopeSet = command.scopeNames?.map { it.lowercase() }?.toSet()

        val accounts = expensesRepo.bankAccountsSnapshot()
        val all = expensesRepo.allWithDetails.first()
        val continuous = when (level) {
            SyncLevel.MANUAL -> emptyList()
            SyncLevel.ALL -> all.filter { it.expense.updatedAt > since }
            SyncLevel.SHARED -> all.filter { details ->
                details.expense.updatedAt > since && inAccountScope(details.expense, scopeSet, accounts)
            }
        }
        val forcedUids = command.uids?.toSet().orEmpty()
        val forced = if (forcedUids.isEmpty()) emptyList() else all.filter { it.expense.uid in forcedUids }
        val candidates = (continuous + forced).distinctBy { it.expense.uid }
        // At MANUAL a pushed copy belongs to the receiving device — a later local deletion is not
        // its business, so no tombstones travel.
        val tombstones = if (level == SyncLevel.MANUAL) emptyList() else expensesRepo.tombstonesSince(since)

        val page = SyncPaging.page(
            candidates, tombstones, command.cursor, command.limit,
            entryKey = { SyncPaging.Key(it.expense.updatedAt, it.expense.uid) },
            tombstoneKey = { SyncPaging.Key(it.deletedAt, it.uid) }
        )

        val recipientNameById = expensesRepo.recipientsSnapshot().associate { it.id to it.name }
        val json = JSONObject()
        json.put(
            SyncDeltaKeys.ENTRIES,
            JSONArray(page.entries.map {
                it.expense.toSyncJson(
                    it.category?.name, it.items,
                    BankAccountTree.bankNameFor(it.expense.bankAccountId, accounts),
                    it.expense.recipientId?.let { id -> recipientNameById[id] }
                )
            })
        )
        json.put(SyncDeltaKeys.TOMBSTONES, JSONArray(page.tombstones.map {
            JSONObject().put(SyncDeltaKeys.UID, it.uid).put(SyncDeltaKeys.DELETED_AT, it.deletedAt)
        }))
        page.nextCursor?.let { json.put(SyncDeltaKeys.NEXT_CURSOR, it) }
        return VoxResult(ok = true, text = json.toString())
    }

    private fun inAccountScope(
        expense: Expense,
        scopeSet: Set<String>?,
        accounts: List<com.voxapps.expenses.data.BankAccount>
    ): Boolean {
        if (scopeSet == null) return true
        val name = BankAccountTree.bankNameFor(expense.bankAccountId, accounts)
            ?: return SyncDeltaKeys.SCOPE_NO_ACCOUNT in scopeSet
        return name.lowercase() in scopeSet
    }

    suspend fun merge(command: VoxCommand): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = lockedMessage)

        val root = try {
            JSONObject(command.text.orEmpty())
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid sync payload")
        }

        val local = expensesRepo.allExpensesSnapshot()
        val localByUid = local.associateBy { it.uid }

        // Same auto-create-by-name convention ExpensesExportImportHandler.import() already uses.
        val existingCategories = expensesRepo.categories.first().toMutableList()
        val nameToId = existingCategories.associate { it.name.lowercase() to it.id }.toMutableMap()
        // Fetched once per merge, not per-entry — see VoxColorPalette.unusedOrRandomColor's
        // precedingColor param.
        val precedingColor = expensesRepo.mostRecentCategoryColor()
        suspend fun categoryIdFor(name: String): Long? =
            nameToId[name.lowercase()] ?: run {
                val newId = expensesRepo.addCategory(
                    name,
                    VoxColorPalette.unusedOrRandomColor(existingCategories.map { it.colorArgb }, precedingColor),
                    existingCategories.size,
                    System.currentTimeMillis()
                )
                if (newId > 0) nameToId[name.lowercase()] = newId
                newId.takeIf { it > 0 }
            }

        val entriesJson = root.optJSONArray(SyncDeltaKeys.ENTRIES) ?: JSONArray()
        val remoteEntries = mutableListOf<Pair<Expense, List<ExpenseLineItem>?>>()
        for (i in 0 until entriesJson.length()) {
            val e = entriesJson.getJSONObject(i)
            val localRow = localByUid[e.optString(SyncDeltaKeys.UID)]
            val categoryId = when {
                !e.has("categoryName") -> localRow?.categoryId
                e.isNull("categoryName") -> null
                else -> categoryIdFor(e.getString("categoryName"))
            }
            val bankAccountId = when {
                !e.has("bank") -> localRow?.bankAccountId
                e.isNull("bank") -> null
                else -> bankAccountIdFor(e.getString("bank"), e.optString("currencyCode"), settings.defaultCurrency)
                    ?: localRow?.bankAccountId
            }
            val recipientId = when {
                !e.has("recipient") -> localRow?.recipientId
                e.isNull("recipient") -> null
                else -> recipientIdFor(e.getString("recipient")) ?: localRow?.recipientId
            }
            val expense = e.toExpense(localRow, categoryId, bankAccountId, recipientId).let {
                // Provenance is stamped exactly once, at insert; an update keeps the local stamp
                // (toExpense already copied it from localRow).
                if (localRow == null) it.copy(
                    originDeviceId = command.sourceDeviceId,
                    originDeviceName = command.sourceDeviceName
                ) else it
            }
            remoteEntries += expense to e.toLineItemsOrNull()
        }

        val tombstonesJson = root.optJSONArray(SyncDeltaKeys.TOMBSTONES) ?: JSONArray()
        val remoteTombstoneUids = (0 until tombstonesJson.length())
            .map { tombstonesJson.getJSONObject(it).optString(SyncDeltaKeys.UID) }
            .toSet()

        // Archived rows are part of the merge: the other device has to learn a record was put away
        // rather than be told nothing and send it back as new.
        val plan = ExpenseSyncIdentity.planMerge(local, remoteEntries.map { it.first }, remoteTombstoneUids)
        val itemsByUid = remoteEntries.associate { (expense, items) -> expense.uid to items }

        for (expense in plan.toInsert) expensesRepo.insertSyncedExpense(expense, itemsByUid[expense.uid] ?: emptyList())
        for (expense in plan.toUpdate) {
            val localId = expensesRepo.getIdByUid(expense.uid) ?: continue
            expensesRepo.updateSyncedExpense(expense.copy(id = localId), itemsByUid[expense.uid])
        }
        for (uid in plan.toDeleteUids) expensesRepo.deleteExpenseByUid(uid)

        return VoxResult(
            ok = true,
            text = JSONObject()
                .put(SyncDeltaKeys.INSERTED, plan.toInsert.size)
                .put(SyncDeltaKeys.UPDATED, plan.toUpdate.size)
                .put(SyncDeltaKeys.DELETED, plan.toDeleteUids.size)
                .toString()
        )
    }

    /**
     * The local account a peer's bank name lands on: exactly one active account of that name, or a
     * fresh one created for it — the convention [ExpensesRepository.accountNamed] already implements
     * for a person naming a bank by hand. Two accounts already carrying the name is a real ambiguity
     * and returns null (the caller keeps the local link rather than guessing); the created account
     * takes the incoming record's own currency, the closest thing to the truth this side has.
     */
    private suspend fun bankAccountIdFor(bankName: String, expenseCurrency: String?, defaultCurrency: String): Long? {
        val name = bankName.trim().takeIf { it.isNotEmpty() } ?: return null
        val existing = expensesRepo.bankAccountsSnapshot()
        val matches = existing.filter {
            !it.archived && it.isAccount && BankAccountTree.bankNameOf(it, existing)?.equals(name, ignoreCase = true) == true
        }
        if (matches.size > 1) return null
        matches.singleOrNull()?.let { return it.id }
        return expensesRepo.accountNamed(name, expenseCurrency?.takeIf { it.isNotBlank() } ?: defaultCurrency)
    }

    /** The counterparty a peer's recipient name lands on — [Recipients.named]'s exactly-one rule,
     *  creating the row where nothing carries the name yet, null (keep the local link) where two
     *  active rows tie. */
    private suspend fun recipientIdFor(recipientName: String): Long? {
        val name = recipientName.trim().takeIf { it.isNotEmpty() } ?: return null
        val existing = expensesRepo.recipientsSnapshot()
        val matches = existing.filter { !it.archived && it.name.trim().equals(name, ignoreCase = true) }
        if (matches.size > 1) return null
        matches.singleOrNull()?.let { return it.id }
        val id = expensesRepo.addRecipient(
            com.voxapps.expenses.domain.accounts.Recipients.newRecipient(name, bankName = null, iban = null, System.currentTimeMillis())
        )
        return id.takeIf { it > 0 }
    }
}

private object ExpenseSyncIdentity : SyncIdentity<Expense> {
    override fun uidOf(record: Expense): String = record.uid
    override fun updatedAtOf(record: Expense): Long = record.updatedAt
}

/** Every nullable field is written as an explicit JSON null rather than omitted — on this wire,
 *  null and absent mean different things (see the class doc comment). */
private fun Expense.toSyncJson(
    categoryName: String?,
    items: List<ExpenseLineItem>,
    /** Derived from the account, sent so a peer can resolve the link by name. */
    bankName: String?,
    recipientName: String?
): JSONObject = JSONObject().apply {
    put(SyncDeltaKeys.UID, uid)
    putNullable("title", title)
    put("totalAmount", totalAmount)
    putNullable("previousBalanceAmount", previousBalanceAmount)
    putNullable("invoiceOwnAmount", invoiceOwnAmount)
    putNullable("netAmount", netAmount)
    putNullable("vatAmount", vatAmount)
    put("currencyCode", currencyCode)
    putNullable("vendor", vendor)
    putNullable("bank", bankName)
    putNullable("recipient", recipientName)
    putNullable("originsJson", originsJson)
    putNullable("location", location)
    put("dateTime", dateTime)
    putNullable("comments", comments)
    putNullable("categoryName", categoryName)
    put("direction", direction.toJsonValue())
    putNullable("receiptImageName", receiptImageName)
    put("isStub", isStub)
    put("source", source.name)
    put("manuallyEdited", manuallyEdited)
    putNullable("archivedAt", archivedAt)
    put("createdAt", createdAt)
    put(SyncDeltaKeys.UPDATED_AT, updatedAt)
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

/** Null when the delta carries no "lineItems" key at all — an older build's entry, whose merge
 *  must leave the local items untouched rather than clear them. */
private fun JSONObject.toLineItemsOrNull(): List<ExpenseLineItem>? {
    if (!has("lineItems")) return null
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

/**
 * The entry as a full local entity: keys the delta carries overwrite, keys it lacks fall back to
 * [local]'s values (a fresh insert falls back to the entity's own defaults). The resolved link ids
 * come in from the caller because resolving them needs the repository.
 */
private fun JSONObject.toExpense(
    local: Expense?,
    categoryId: Long?,
    bankAccountId: Long?,
    recipientId: Long?
): Expense = Expense(
    uid = optString(SyncDeltaKeys.UID),
    title = stringOr("title") { local?.title },
    totalAmount = if (has("totalAmount")) optDouble("totalAmount") else local?.totalAmount ?: 0.0,
    previousBalanceAmount = doubleOr("previousBalanceAmount") { local?.previousBalanceAmount },
    invoiceOwnAmount = doubleOr("invoiceOwnAmount") { local?.invoiceOwnAmount },
    netAmount = doubleOr("netAmount") { local?.netAmount },
    vatAmount = doubleOr("vatAmount") { local?.vatAmount },
    currencyCode = if (has("currencyCode")) optString("currencyCode") else local?.currencyCode ?: "",
    vendor = stringOr("vendor") { local?.vendor },
    bankAccountId = bankAccountId,
    recipientId = recipientId,
    originsJson = stringOr("originsJson") { local?.originsJson },
    location = stringOr("location") { local?.location },
    dateTime = if (has("dateTime")) optLong("dateTime") else local?.dateTime ?: 0L,
    comments = stringOr("comments") { local?.comments },
    categoryId = categoryId,
    direction = if (has("direction")) optTransactionDirection() else local?.direction
        ?: com.voxapps.expenses.data.TransactionDirection.OUTGOING,
    receiptImageName = stringOr("receiptImageName") { local?.receiptImageName },
    isStub = if (has("isStub")) optBoolean("isStub", false) else local?.isStub ?: false,
    source = if (has("source")) {
        ExpenseSource.entries.firstOrNull { it.name == optString("source") } ?: local?.source ?: ExpenseSource.MANUAL
    } else {
        local?.source ?: ExpenseSource.MANUAL
    },
    manuallyEdited = if (has("manuallyEdited")) optBoolean("manuallyEdited", false) else local?.manuallyEdited ?: false,
    originDeviceId = local?.originDeviceId,
    originDeviceName = local?.originDeviceName,
    archivedAt = longOr("archivedAt") { local?.archivedAt },
    createdAt = if (has("createdAt")) optLong("createdAt") else local?.createdAt ?: System.currentTimeMillis(),
    updatedAt = optLong(SyncDeltaKeys.UPDATED_AT)
)

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private inline fun JSONObject.stringOr(key: String, fallback: () -> String?): String? = when {
    !has(key) -> fallback()
    isNull(key) -> null
    else -> optString(key)
}

private inline fun JSONObject.doubleOr(key: String, fallback: () -> Double?): Double? = when {
    !has(key) -> fallback()
    isNull(key) -> null
    else -> optDouble(key).takeIf { !it.isNaN() }
}

private inline fun JSONObject.longOr(key: String, fallback: () -> Long?): Long? = when {
    !has(key) -> fallback()
    isNull(key) -> null
    else -> optLong(key)
}
