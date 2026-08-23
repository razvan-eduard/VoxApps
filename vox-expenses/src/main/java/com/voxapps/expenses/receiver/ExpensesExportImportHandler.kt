package com.voxapps.expenses.receiver

import android.content.Context
import android.net.Uri
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.restoreFromBackup
import com.voxapps.attachments.toBackupJson
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.backup.VoxAttachmentZipUtil
import com.voxapps.backup.VoxBiometricGate
import com.voxapps.backup.mergeByName
import com.voxapps.backup.optStringOrNull
import com.voxapps.backup.VoxImportMode
import com.voxapps.backup.VoxSettingsRoundTrip
import com.voxapps.backup.VoxSnapshotReplaceImporter
import com.voxapps.design.toEnumOrNull
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.DuplicateRuleDao
import com.voxapps.expenses.data.DuplicateRuleEntity
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExchangeRateApiKeyStore
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.ExpenseRemapFields
import com.voxapps.expenses.data.RemapRuleEntity
import com.voxapps.expenses.data.RemapRuleJson
import com.voxapps.expenses.data.RecurrenceFrequency
import com.voxapps.expenses.data.RecurringPayment
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.llm.optTransactionDirection
import com.voxapps.expenses.domain.recurring.RecurringPaymentRepository
import com.voxapps.expenses.domain.llm.toJsonValue
import com.voxapps.expenses.state.SessionManager
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ExpensesExportImportHandler"

/**
 * Vox Hub's export/import for this app, extracted from the BroadcastReceiver so it's unit-testable
 * without Android (mirrors [ExpensesReadResponder] / vox-notes' NotesExportImportHandler). Respects
 * the same biometric-lock gate as reads — an export/import request while the app is locked never
 * touches the DB.
 *
 * [duplicateRuleDao] is injected directly (same convention as [attachmentDao]) rather than routed
 * through [ExpensesRepository] — that repository already keeps its own `duplicateRuleDao` reference
 * private (used only internally for the auto-check pass), and `DuplicateRuleDao` is already injected
 * directly into `ExpensesStateManager` elsewhere in this codebase for the same reason, so direct
 * injection here isn't a new pattern.
 */
class ExpensesExportImportHandler(
    private val context: Context,
    private val settingsRepo: ExpensesSettingsRepository,
    private val sessionManager: SessionManager,
    private val expensesRepo: ExpensesRepository,
    private val attachmentDao: AttachmentDao,
    private val duplicateRuleDao: DuplicateRuleDao,
    private val recurringPaymentRepo: RecurringPaymentRepository,
    private val lockedMessage: String
) {
    suspend fun export(
        scope: String = VoxIpc.EXPORT_SCOPE_BOTH,
        includeSecrets: Boolean = false,
        includePhotos: Boolean = false
    ): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = VoxBiometricGate.isLocked(settings.isBiometricRequired, settings.sessionTimeoutMinutes, sessionManager::isSessionValid)
        if (locked) return VoxResult(ok = false, text = lockedMessage)

        val json = JSONObject()
        var attachmentUri: String? = null
        var secondaryAttachmentUri: String? = null
        if (scope != VoxIpc.EXPORT_SCOPE_DATA) {
            json.put("settings", settings.toJson())
            // The exchange-rate API key is a real secret, kept entirely outside ExpensesSettings/
            // DataStore (see ExchangeRateApiKeyStore's own doc comment) — only included when the
            // user explicitly opts in from Hub's export screen.
            if (includeSecrets) {
                ExchangeRateApiKeyStore.get(context)?.let { json.put("exchangeRateApiKey", it) }
            }
        }
        if (scope != VoxIpc.EXPORT_SCOPE_SETTINGS) {
            val categories = expensesRepo.categories.first()
            val spendingLimits = expensesRepo.spendingLimits.first()
            val expensesWithDetails = expensesRepo.expensesWithDetails.first()
            val remapRules = expensesRepo.remapRulesSnapshot()
            json.put("categories", JSONArray(categories.map { it.toJson() }))
            json.put("spendingLimits", JSONArray(spendingLimits.map { it.toJson() }))
            val allAttachmentFileNames = mutableListOf<String>()
            json.put(
                "expenses",
                JSONArray(
                    expensesWithDetails.map {
                        val attachments = attachmentDao.getFor(ExpensesAttachments.RECORD_TYPE, it.expense.id)
                        allAttachmentFileNames += attachments.map { a -> a.fileName }
                        it.expense.toJson(it.items, attachments)
                    }
                )
            )
            json.put("remapRules", JSONArray(remapRules.map { it.toJson() }))
            json.put(
                "learnedFieldCorrections",
                JSONArray(
                    expensesRepo.learnedFieldCorrectionsSnapshot().map { c ->
                        JSONObject().put("garbageKey", c.garbageKey).put("fix", c.fix)
                            .put("consecutiveCount", c.consecutiveCount)
                            .put("quarantined", c.quarantined).put("updatedAt", c.updatedAt)
                    }
                )
            )
            val duplicateRules = duplicateRuleDao.getAll()
            json.put("duplicateRules", JSONArray(duplicateRules.map { it.toJson() }))
            // Dismissals ride along with confirmations: both are answers a person gave, and a restore
            // that kept only the yesses would start proposing everything they had already refused.
            json.put("recurringPayments", JSONArray(recurringPaymentRepo.snapshot().map { it.toJson() }))
            json.put("bankAccounts", JSONArray(expensesRepo.bankAccountsSnapshot().map { it.toJson() }))

            if (includePhotos) {
                val names = expensesWithDetails.mapNotNull { it.expense.receiptImageName?.takeIf { n -> n.isNotBlank() } }
                attachmentUri = buildReceiptsZip(names)?.toString()
                // Separate zip/field from the receipts one above — manually-added attachments (see
                // :core:attachments) live in their own filesDir/attachments/ dir, distinct from the
                // original-scan receipts/ dir, and ride VoxResult's secondaryAttachmentUri rather
                // than folding into buildReceiptsZip's existing, unchanged output.
                secondaryAttachmentUri = buildAttachmentsZip(allAttachmentFileNames)?.toString()
            }
        }
        return VoxResult(ok = true, text = json.toString(), attachmentUri = attachmentUri, secondaryAttachmentUri = secondaryAttachmentUri)
    }

    /** Zips manually-added attachment files (see :core:attachments) into a fresh file under
     *  cacheDir and grants Hub read access — same shape as [buildReceiptsZip], kept separate since
     *  it covers a different directory (filesDir/attachments/, not filesDir/receipts/). Also bundles
     *  each attachment's sibling raw-OCR-text file, if present (see
     *  [OcrResultReceiver.writeOcrTextSibling]) — those exist for the same
     *  retry-without-rescanning reason [buildReceiptsZip] already bundles its own. */
    private fun buildAttachmentsZip(fileNames: List<String>): Uri? =
        VoxAttachmentZipUtil.build(
            context, ExpensesAttachments.DIR, fileNames, ExpensesAttachments.FILE_PROVIDER_AUTHORITY, sidecarSuffix = ".txt"
        )

    /** Same shape as [extractReceiptsZip], kept separate since it targets a different directory. */
    private fun extractAttachmentsZip(uri: Uri) =
        VoxAttachmentZipUtil.extract(context, ExpensesAttachments.DIR, uri)

    /**
     * Zips just the receipt-image files (plus their sibling raw-OCR-text files, if present — those
     * exist purely so a stub expense can retry LLM cleanup without re-scanning the paper receipt;
     * dropping them here would silently break that retry path after a restore) into a fresh file
     * under cacheDir, and grants Hub read access to it. Returns null (no attachment) if there's
     * nothing to bundle or on any I/O failure — photo bundling is always best-effort, never blocks
     * the JSON export itself.
     */
    private fun buildReceiptsZip(names: List<String>): Uri? =
        VoxAttachmentZipUtil.build(
            context, "receipts", names, ExpensesAttachments.FILE_PROVIDER_AUTHORITY,
            sidecarSuffix = ".txt", zipFilePrefix = "export_receipts"
        )

    private fun extractReceiptsZip(uri: Uri) =
        VoxAttachmentZipUtil.extract(context, "receipts", uri)

    suspend fun import(payloadJson: String, importMode: VoxImportMode = VoxImportMode.MERGE): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = VoxBiometricGate.isLocked(settings.isBiometricRequired, settings.sessionTimeoutMinutes, sessionManager::isSessionValid)
        if (locked) return VoxResult(ok = false, text = lockedMessage)

        val root = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = "Invalid import payload")
        }

        root.optJSONObject("settings")?.let { settingsRepo.restoreSettings(it.toExpensesSettings()) }
        // Only present if the export was made with includeSecrets on — absent means "leave the
        // current on-device key alone", same semantics as Commander's secret fields.
        root.optStringOrNull("exchangeRateApiKey")?.let { ExchangeRateApiKeyStore.set(context, it) }

        // Injected by Hub's ExportImportUtil.parseImportDocument() from the outer export document's
        // timestamp. Defaults to 0L (never true against any real createdAt) so a payload missing
        // this field — an old hand-crafted or pre-fix import — fails safe by deleting nothing,
        // rather than silently reverting to "delete everything that existed at import time".
        val exportedAt = root.optLong("exported_at", 0L)

        // Stage any bundled receipt photos before the expense-insert loop below references them by
        // filename, so thumbnails resolve immediately once the import completes. Best-effort: a
        // failure here never fails the rest of the import, it just means photos are missing.
        root.optStringOrNull("receiptsZipUri")?.let { uriString ->
            try {
                extractReceiptsZip(Uri.parse(uriString))
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to import receipt photos from $uriString — continuing without them", e)
            }
        }
        // Separate field/zip from receiptsZipUri above — see buildAttachmentsZip's doc comment.
        root.optStringOrNull("attachmentsZipUri")?.let { uriString ->
            try {
                extractAttachmentsZip(Uri.parse(uriString))
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to import attachments from $uriString — continuing without them", e)
            }
        }

        val importedCategories = root.optJSONArray("categories") ?: JSONArray()
        val categoryMerge = mergeByName(
            imported = importedCategories,
            existing = expensesRepo.categories.first(),
            nameOf = { it.name },
            idOf = { it.id },
            importedNameOf = { it.optString("name") },
            create = { c, name ->
                expensesRepo.addCategory(
                    name,
                    c.optLong("colorArgb"),
                    c.optInt("position"),
                    c.optLong("createdAt", System.currentTimeMillis()),
                    c.optString("icon").takeIf { it.isNotBlank() }
                )
            }
        )
        val importedIdToLocalId = categoryMerge.idMap
        val categoriesCreated = categoryMerge.created

        // Which category records with no opinion fall back to is one choice rather than a property
        // of a row, so it is restored like a setting: moved through the same id map the rows use,
        // onto the local category the marked one merged into. A backup carrying no mark at all
        // leaves this device's own choice standing, since silence is not an instruction to change
        // it. See [com.voxapps.expenses.data.Category.isDefault] for the invariant this keeps.
        (0 until importedCategories.length())
            .map { importedCategories.getJSONObject(it) }
            .firstOrNull { it.optBoolean("isDefault") }
            ?.let { importedIdToLocalId[it.optLong("id")] }
            ?.let { expensesRepo.setDefaultCategory(it) }

        // Merged by (origin, matchJson), not replace-by-snapshot — wiping locally learned rules on
        // every restore would un-learn streaks built up on this device since the backup was taken.
        // Category ids inside setJson are the exporting device's; remapped through the same id map
        // the expense rows use, and an entry whose category didn't survive the trip is dropped from
        // the set rather than imported dangling.
        fun remapSetCategoryIds(setJson: String): String? {
            val set = RemapRuleJson.decode(setJson).toMutableMap()
            set[ExpenseRemapFields.ID_CATEGORY_ID]?.let { imported ->
                val local = imported.toLongOrNull()?.let { importedIdToLocalId[it] }
                if (local == null) set.remove(ExpenseRemapFields.ID_CATEGORY_ID)
                else set[ExpenseRemapFields.ID_CATEGORY_ID] = local.toString()
            }
            return if (set.isEmpty()) null else RemapRuleJson.encode(set)
        }
        val importedRemapRules = root.optJSONArray("remapRules") ?: JSONArray()
        for (i in 0 until importedRemapRules.length()) {
            val r = importedRemapRules.getJSONObject(i)
            val matchJson = r.optString("matchJson").takeIf { it.isNotBlank() } ?: continue
            val setJson = remapSetCategoryIds(r.optString("setJson")) ?: continue
            // A backup from the auto-learning era carries LEARNED rows with a streak count: ones
            // that had reached activation convert to plain USER rules (the v21→v22 migration's
            // conversion); the rest never answered anything and are skipped.
            val origin = r.optString("origin", RemapRuleEntity.ORIGIN_USER)
            if (origin == "LEARNED" && r.optInt("consecutiveCount", 0) < 3) continue
            expensesRepo.mergeRemapRule(
                RemapRuleEntity(
                    name = r.optString("name"),
                    matchJson = matchJson,
                    setJson = setJson,
                    origin = if (origin == "LEARNED") RemapRuleEntity.ORIGIN_USER else origin,
                    enabled = r.optBoolean("enabled", true),
                    sortOrder = r.optInt("sortOrder", 0),
                    updatedAt = r.optLong("updatedAt", System.currentTimeMillis()),
                    fuzzJson = r.optString("fuzzJson", "{}")
                )
            )
        }
        // Back-compat: a backup from before the re-map engine carries merchantCategoryMemory rows —
        // the same conversion, activated streaks only.
        val importedMerchantMemory = root.optJSONArray("merchantCategoryMemory") ?: JSONArray()
        for (i in 0 until importedMerchantMemory.length()) {
            val m = importedMerchantMemory.getJSONObject(i)
            val vendorKey = m.optString("vendorKey").takeIf { it.isNotBlank() } ?: continue
            val localCategoryId = importedIdToLocalId[m.optLong("categoryId")] ?: continue
            if (m.optInt("consecutiveCount", 0) < 3) continue
            expensesRepo.mergeRemapRule(
                RemapRuleEntity(
                    name = vendorKey,
                    matchJson = RemapRuleJson.encode(mapOf(ExpenseRemapFields.ID_VENDOR to vendorKey)),
                    setJson = RemapRuleJson.encode(mapOf(ExpenseRemapFields.ID_CATEGORY_ID to localCategoryId.toString())),
                    origin = RemapRuleEntity.ORIGIN_USER,
                    updatedAt = m.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }

        // Same upsert-not-replace stance as merchantCategoryMemory above — restore keeps the higher
        // confirmation count, and quarantine survives the merge in both directions.
        val importedCorrections = root.optJSONArray("learnedFieldCorrections") ?: JSONArray()
        for (i in 0 until importedCorrections.length()) {
            val c = importedCorrections.getJSONObject(i)
            val key = c.optString("garbageKey").takeIf { it.isNotBlank() } ?: continue
            val fix = c.optString("fix").takeIf { it.isNotBlank() } ?: continue
            expensesRepo.restoreLearnedFieldCorrection(
                com.voxapps.fieldmemory.LearnedFieldCorrection(
                    garbageKey = key,
                    fix = fix,
                    consecutiveCount = c.optInt("consecutiveCount", 1),
                    quarantined = c.optBoolean("quarantined", false),
                    updatedAt = c.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }

        // Merged by name (mirrors categories above), not replace-by-snapshot — the two rules
        // seeded on every fresh install would otherwise duplicate on each restore cycle, and a
        // rule only ever added on this specific device shouldn't be wiped by a backup taken
        // elsewhere.
        val existingRules = duplicateRuleDao.getAll()
        val ruleNameToRule = existingRules.associateBy { it.name.lowercase() }
        val importedRules = root.optJSONArray("duplicateRules") ?: JSONArray()
        var rulesCreated = 0
        for (i in 0 until importedRules.length()) {
            val r = importedRules.getJSONObject(i)
            val name = r.optString("name").trim()
            if (name.isEmpty()) continue
            val fieldIdsArray = r.optJSONArray("fieldIds") ?: JSONArray()
            val fieldIds = (0 until fieldIdsArray.length()).map { fieldIdsArray.optString(it) }
            val existing = ruleNameToRule[name.lowercase()]
            if (existing != null) {
                duplicateRuleDao.update(
                    existing.copy(
                        fieldIds = fieldIds,
                        combinator = r.optString("combinator", existing.combinator),
                        enabled = r.optBoolean("enabled", existing.enabled),
                        sortOrder = r.optInt("sortOrder", existing.sortOrder),
                        appliesAutomatically = r.optBoolean("appliesAutomatically", existing.appliesAutomatically),
                        fuzzyMatchEnabled = r.optBoolean("fuzzyMatchEnabled", existing.fuzzyMatchEnabled)
                    )
                )
            } else {
                duplicateRuleDao.upsert(
                    DuplicateRuleEntity(
                        name = name,
                        fieldIds = fieldIds,
                        combinator = r.optString("combinator", "AND"),
                        enabled = r.optBoolean("enabled", true),
                        sortOrder = r.optInt("sortOrder", 0),
                        appliesAutomatically = r.optBoolean("appliesAutomatically", true),
                        fuzzyMatchEnabled = r.optBoolean("fuzzyMatchEnabled", true)
                    )
                )
                rulesCreated++
            }
        }

        // Snapshot pre-existing spending limits/expenses, insert every imported one, then reconcile
        // pre-existing rows per the user's chosen import mode (see VoxSnapshotReplaceImporter).
        // Categories are untouched here — merged by name above.
        if (root.has("spendingLimits")) {
            val preExistingLimits = expensesRepo.spendingLimits.first()
            val importedLimits = root.optJSONArray("spendingLimits") ?: JSONArray()

            VoxSnapshotReplaceImporter.restore(
                mode = importMode,
                imported = (0 until importedLimits.length()).map { importedLimits.getJSONObject(it) },
                preExisting = preExistingLimits,
                exportedAt = exportedAt,
                createdAtOf = { it.createdAt },
                insert = { l ->
                    val importedCategoryId = if (l.has("categoryId") && !l.isNull("categoryId")) l.optLong("categoryId") else null
                    val categoryId = importedCategoryId?.let { importedIdToLocalId[it] }
                    expensesRepo.addSpendingLimit(
                        categoryId, l.optDouble("amountHomeCurrency"), l.optString("period"),
                        createdAt = l.optLong("createdAt", System.currentTimeMillis())
                    )
                },
                delete = { expensesRepo.deleteSpendingLimit(it) }
            )
        }

        // Merged by digits rather than replaced by snapshot: the digits are what identify a card
        // anywhere, so a restore onto a device that already met the same card lands on the row it
        // already has instead of colliding with the unique index. The parent link is remapped
        // through the same map, and an account whose parent did not survive the trip becomes a root
        // rather than pointing at nothing.
        val importedAccountToLocal = mutableMapOf<Long, Long>()
        if (root.has("bankAccounts")) {
            val imported = root.optJSONArray("bankAccounts") ?: JSONArray()
            val existing = expensesRepo.bankAccountsSnapshot().associateBy { it.digits.lowercase() }
            val entries = (0 until imported.length()).map { imported.getJSONObject(it) }
            for (a in entries) {
                val digits = a.optString("digits").takeIf { it.isNotBlank() } ?: continue
                val local = existing[digits.lowercase()]?.id
                    ?: expensesRepo.addBankAccount(a.toBankAccount(parentId = null)).takeIf { it > 0 }
                    ?: continue
                importedAccountToLocal[a.optLong("id")] = local
            }
            // A second pass, because a card may be listed before the account it belongs to.
            for (a in entries) {
                val local = importedAccountToLocal[a.optLong("id")] ?: continue
                val parent = a.optLong("parentId").takeIf { a.has("parentId") && !a.isNull("parentId") }
                    ?.let { importedAccountToLocal[it] } ?: continue
                expensesRepo.bankAccountsSnapshot().firstOrNull { it.id == local }
                    ?.let { expensesRepo.updateBankAccount(it.copy(parentId = parent)) }
            }
        }

        if (root.has("recurringPayments")) {
            val preExisting = recurringPaymentRepo.snapshot()
            val imported = root.optJSONArray("recurringPayments") ?: JSONArray()

            VoxSnapshotReplaceImporter.restore(
                mode = importMode,
                imported = (0 until imported.length()).map { imported.getJSONObject(it) },
                preExisting = preExisting,
                exportedAt = exportedAt,
                createdAtOf = { it.createdAt },
                insert = { p ->
                    val importedCategoryId =
                        if (p.has("categoryId") && !p.isNull("categoryId")) p.optLong("categoryId") else null
                    recurringPaymentRepo.restore(p.toRecurringPayment(importedCategoryId?.let { importedIdToLocalId[it] }))
                },
                delete = { recurringPaymentRepo.delete(it) }
            )
        }

        var expensesCreated = 0
        if (root.has("expenses")) {
            val preExistingExpenses = expensesRepo.expensesSnapshot()
            val importedExpenses = root.optJSONArray("expenses") ?: JSONArray()

            expensesCreated = VoxSnapshotReplaceImporter.restore(
                mode = importMode,
                imported = (0 until importedExpenses.length()).map { importedExpenses.getJSONObject(it) },
                preExisting = preExistingExpenses,
                exportedAt = exportedAt,
                createdAtOf = { it.createdAt },
                insert = { e ->
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
                    val newExpenseId = expensesRepo.addExpense(
                        title = e.optStringOrNull("title"),
                        totalAmount = e.optDouble("totalAmount"),
                        currencyCode = e.optString("currencyCode"),
                        vendor = e.optStringOrNull("vendor"),
                        bank = e.optStringOrNull("bank"),
                        // Through the same map the account rows were merged by, so a record restored
                        // onto a device that already knew the card points at the row it already has.
                        bankAccountId = e.optLong("bankAccountId")
                            .takeIf { e.has("bankAccountId") && !e.isNull("bankAccountId") }
                            ?.let { importedAccountToLocal[it] },
                        location = e.optStringOrNull("location"),
                        dateTime = e.optLong("dateTime", System.currentTimeMillis()),
                        comments = e.optStringOrNull("comments"),
                        categoryId = categoryId,
                        direction = e.optTransactionDirection(),
                        items = items,
                        imageName = e.optStringOrNull("receiptImageName"),
                        // Preserved from the source device, never re-stamped to "now" — re-stamping
                        // would make a later re-sync from the same backup see this row as freshly
                        // created and permanently immune to being correctly replaced, silently
                        // reintroducing the bug this createdAt filter exists to fix.
                        createdAt = e.optLong("createdAt", System.currentTimeMillis()),
                        // Old pre-existing rows are deleted AFTER every insert (VoxSnapshotReplaceImporter's
                        // own order), so they're still present here and would otherwise get misdetected as
                        // duplicates of the very rows they're about to be replaced by — import must never be
                        // blocked by this check (RecordSource.HUB_IMPORT: another install's already-validated data).
                        checkForDuplicate = false,
                        source = e.optExpenseSource(),
                        manuallyEdited = e.optBoolean("manuallyEdited", false)
                    )
                    if (newExpenseId > 0) {
                        attachmentDao.restoreFromBackup(
                            ExpensesAttachments.RECORD_TYPE, newExpenseId, e.optJSONArray("attachments") ?: JSONArray()
                        )
                    }
                    newExpenseId
                },
                delete = { expensesRepo.deleteExpenseById(it.id) }
            )
        }

        return VoxResult(
            ok = true,
            text = "$expensesCreated expenses imported, $categoriesCreated new categories " +
                "(${importedCategories.length() - categoriesCreated} matched existing), " +
                "$rulesCreated new duplicate rules (${importedRules.length() - rulesCreated} matched existing)"
        )
    }

}

// Gson reflection over the whole data class, not a hand-maintained field list — a manual allowlist
// silently falls behind every time a new setting is added (this one was 20 of ~50 fields before this
// fix, including the very duplicateCheckMode*/duplicateRuleSetGlobalCombinator settings that govern
// [DuplicateRuleEntity] above). appCacheJson/onboardingCompleted are the two deliberate exclusions
// (device-local cache/UI state, not portable user data — see their own doc comments); reset rather
// than omitted so import's Gson.fromJson always has every field present. Mirrors vox-commander's
// CommanderExportHandler, the only handler in this codebase that didn't suffer this drift.
private fun ExpensesSettings.toJson(): JSONObject =
    JSONObject(VoxSettingsRoundTrip.toJson(copy(appCacheJson = null, onboardingCompleted = false)))

/** Returns Room/DataStore defaults for [ExpensesSettings] if [this] isn't valid JSON for it (e.g. a
 *  corrupt/foreign import file). [paymentSourcePackages]/[bankingSourcePackages]/[locationCacheTtl]
 *  get an extra null-coalesce afterward — Gson leaves these genuinely null (not their data class
 *  defaults) when an old/foreign payload is missing that key entirely, the same failure mode
 *  [com.voxapps.commander.receiver.CommanderExportHandler.parsePortableSettings] already guards
 *  against for its own fields. */
private fun JSONObject.toExpensesSettings(): ExpensesSettings =
    VoxSettingsRoundTrip.parseOrDefault(toString(), ExpensesSettings::class.java, ExpensesSettings()) { parsed ->
        parsed.copy(
            paymentSourcePackages = parsed.paymentSourcePackages ?: emptySet(),
            bankingSourcePackages = parsed.bankingSourcePackages ?: emptySet(),
            locationCacheTtl = parsed.locationCacheTtl ?: "ONE_DAY"
        )
    }

private fun BankAccount.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("digits", digits)
    put("kind", kind)
    parentId?.let { put("parentId", it) }
    label?.let { put("label", it) }
    put("currencyCode", currencyCode)
    bankName?.let { put("bankName", it) }
    icon?.let { put("icon", it) }
    put("createdAt", createdAt)
    put("autoCreated", autoCreated)
}

/** [parentId] is supplied by the caller after the id map exists — a card may be listed before the
 *  account it belongs to. */
private fun JSONObject.toBankAccount(parentId: Long?): BankAccount = BankAccount(
    digits = optString("digits"),
    kind = optString("kind"),
    parentId = parentId,
    label = optStringOrNull("label"),
    currencyCode = optString("currencyCode"),
    bankName = optStringOrNull("bankName"),
    icon = optStringOrNull("icon"),
    createdAt = optLong("createdAt", System.currentTimeMillis()),
    autoCreated = optBoolean("autoCreated", false)
)

private fun Category.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("colorArgb", colorArgb)
    put("position", position)
    put("createdAt", createdAt)
    put("isDefault", isDefault)
    icon?.let { put("icon", it) }
}

private fun RecurringPayment.toJson(): JSONObject = JSONObject().apply {
    put("vendorKey", vendorKey)
    put("vendorLabel", vendorLabel)
    put("frequency", frequency.name)
    put("interval", interval)
    put("dueDayOfMonth", dueDayOfMonth)
    put("expectedAmount", expectedAmount)
    put("currency", currency)
    put("categoryId", categoryId)
    put("lastSeenAt", lastSeenAt)
    put("occurrences", occurrences)
    put("missedCycles", missedCycles)
    put("confirmedAt", confirmedAt)
    put("notifiedForDueAt", notifiedForDueAt)
    put("dismissed", dismissed)
    put("createdAt", createdAt)
}

/** Rebuilds an arrangement from backup. [localCategoryId] is resolved by the caller, since category
 *  ids are reassigned on import and a stale one would colour the row after somebody else's category. */
private fun JSONObject.toRecurringPayment(localCategoryId: Long?): RecurringPayment = RecurringPayment(
    vendorKey = optString("vendorKey"),
    vendorLabel = optString("vendorLabel"),
    frequency = runCatching { RecurrenceFrequency.valueOf(optString("frequency")) }
        .getOrDefault(RecurrenceFrequency.MONTHLY),
    interval = optInt("interval", 1).coerceAtLeast(1),
    dueDayOfMonth = optInt("dueDayOfMonth", 1),
    expectedAmount = if (isNull("expectedAmount")) null else optDouble("expectedAmount"),
    currency = if (isNull("currency")) null else optString("currency"),
    categoryId = localCategoryId,
    lastSeenAt = optLong("lastSeenAt"),
    occurrences = optInt("occurrences", 1),
    missedCycles = optInt("missedCycles", 0),
    confirmedAt = if (isNull("confirmedAt")) null else optLong("confirmedAt"),
    notifiedForDueAt = if (isNull("notifiedForDueAt")) null else optLong("notifiedForDueAt"),
    dismissed = optBoolean("dismissed", false),
    createdAt = optLong("createdAt", System.currentTimeMillis())
)

private fun SpendingLimit.toJson(): JSONObject = JSONObject().apply {
    put("categoryId", categoryId)
    put("amountHomeCurrency", amountHomeCurrency)
    put("period", period)
    put("createdAt", createdAt)
}

private fun DuplicateRuleEntity.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("fieldIds", JSONArray(fieldIds))
    put("combinator", combinator)
    put("enabled", enabled)
    put("sortOrder", sortOrder)
    put("appliesAutomatically", appliesAutomatically)
    put("fuzzyMatchEnabled", fuzzyMatchEnabled)
}

private fun RemapRuleEntity.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("matchJson", matchJson)
    put("setJson", setJson)
    put("origin", origin)
    put("fuzzJson", fuzzJson)
    put("enabled", enabled)
    put("sortOrder", sortOrder)
    put("updatedAt", updatedAt)
}

private fun Expense.toJson(items: List<ExpenseLineItem>, attachments: List<AttachmentEntity> = emptyList()): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("totalAmount", totalAmount)
    put("currencyCode", currencyCode)
    put("vendor", vendor)
    put("bank", bank)
    bankAccountId?.let { put("bankAccountId", it) }
    put("location", location)
    put("dateTime", dateTime)
    put("comments", comments)
    put("categoryId", categoryId)
    put("direction", direction.toJsonValue())
    put("receiptImageName", receiptImageName)
    put("createdAt", createdAt)
    put("source", source.name)
    put("manuallyEdited", manuallyEdited)
    put("items", JSONArray(items.map { it.toJson() }))
    put("attachments", JSONArray(attachments.map { it.toBackupJson() }))
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


/** Lenient parse for a field that didn't exist before this export/import round-trip supported it —
 *  older backups (and any unrecognized/corrupt value) fall back to [ExpenseSource.MANUAL]. */
private fun JSONObject.optExpenseSource(key: String = "source"): ExpenseSource =
    optStringOrNull(key).toEnumOrNull<ExpenseSource>() ?: ExpenseSource.MANUAL

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
