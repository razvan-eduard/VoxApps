package com.voxapps.expenses.receiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.DuplicateRuleDao
import com.voxapps.expenses.data.DuplicateRuleEntity
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExchangeRateApiKeyStore
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.MerchantCategoryMemory
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.llm.optTransactionDirection
import com.voxapps.expenses.domain.llm.toJsonValue
import com.voxapps.expenses.state.SessionManager
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "ExpensesExportImportHandler"
private val gson = Gson()

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
    private val duplicateRuleDao: DuplicateRuleDao
) {
    suspend fun export(
        scope: String = VoxIpc.EXPORT_SCOPE_BOTH,
        includeSecrets: Boolean = false,
        includePhotos: Boolean = false
    ): VoxResult {
        val settings = settingsRepo.getSnapshot()
        val locked = settings.isBiometricRequired &&
            !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
        if (locked) return VoxResult(ok = false, text = ExpensesReadResponder.LOCKED_MESSAGE)

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
            val merchantCategoryMemory = expensesRepo.merchantCategoryMemorySnapshot()
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
            json.put("merchantCategoryMemory", JSONArray(merchantCategoryMemory.map { it.toJson() }))
            val duplicateRules = duplicateRuleDao.observeAll().first()
            json.put("duplicateRules", JSONArray(duplicateRules.map { it.toJson() }))

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
     *  retry-without-rescanning reason [buildReceiptsZip] already bundles its own, and were
     *  previously dropped silently here, unlike there. */
    private fun buildAttachmentsZip(fileNames: List<String>): Uri? {
        if (fileNames.isEmpty()) return null
        val attachmentsDir = File(context.filesDir, ExpensesAttachments.DIR)
        return try {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportsDir, "export_attachments_${UUID.randomUUID()}.zip")
            var wroteAny = false
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (name in fileNames) {
                    val file = File(attachmentsDir, name)
                    if (file.exists()) {
                        zos.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        wroteAny = true
                    }
                    val txt = File(attachmentsDir, name.substringBeforeLast('.') + ".txt")
                    if (txt.exists()) {
                        zos.putNextEntry(ZipEntry(txt.name))
                        txt.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            if (!wroteAny) {
                zipFile.delete()
                return null
            }
            val uri = FileProvider.getUriForFile(context, "com.voxapps.expenses.fileprovider", zipFile)
            context.grantUriPermission(VoxIpc.HUB_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to build attachments export zip", e)
            null
        }
    }

    /** Same shape as [extractReceiptsZip], kept separate since it targets a different directory. */
    private fun extractAttachmentsZip(uri: Uri) {
        val attachmentsDir = File(context.filesDir, ExpensesAttachments.DIR).apply { mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val safeName = File(entry.name).name
                        if (safeName.isNotBlank()) {
                            FileOutputStream(File(attachmentsDir, safeName)).use { fos -> zis.copyTo(fos) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    /**
     * Zips just the receipt-image files (plus their sibling raw-OCR-text files, if present — those
     * exist purely so a stub expense can retry LLM cleanup without re-scanning the paper receipt;
     * dropping them here would silently break that retry path after a restore) into a fresh file
     * under cacheDir, and grants Hub read access to it. Returns null (no attachment) if there's
     * nothing to bundle or on any I/O failure — photo bundling is always best-effort, never blocks
     * the JSON export itself.
     */
    private fun buildReceiptsZip(names: List<String>): Uri? {
        if (names.isEmpty()) return null
        val receiptsDir = File(context.filesDir, "receipts")
        return try {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportsDir, "export_receipts_${UUID.randomUUID()}.zip")
            var wroteAny = false
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (name in names) {
                    val img = File(receiptsDir, name)
                    if (img.exists()) {
                        zos.putNextEntry(ZipEntry(name))
                        img.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        wroteAny = true
                    }
                    val txt = File(receiptsDir, name.substringBeforeLast('.') + ".txt")
                    if (txt.exists()) {
                        zos.putNextEntry(ZipEntry(txt.name))
                        txt.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            if (!wroteAny) {
                zipFile.delete()
                return null
            }
            val uri = FileProvider.getUriForFile(context, "com.voxapps.expenses.fileprovider", zipFile)
            context.grantUriPermission(VoxIpc.HUB_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to build receipts export zip", e)
            null
        }
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

        // Upserted by vendorKey, not replace-by-snapshot like spendingLimits/expenses below — wiping
        // locally learned mappings on every restore would un-learn correction streaks built up on
        // this device since the backup was taken.
        val importedMerchantMemory = root.optJSONArray("merchantCategoryMemory") ?: JSONArray()
        for (i in 0 until importedMerchantMemory.length()) {
            val m = importedMerchantMemory.getJSONObject(i)
            val vendorKey = m.optString("vendorKey").takeIf { it.isNotBlank() } ?: continue
            val localCategoryId = importedIdToLocalId[m.optLong("categoryId")] ?: continue
            expensesRepo.upsertMerchantCategoryMemory(
                vendorKey, localCategoryId, m.optInt("consecutiveCount", 1), m.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        // Merged by name (mirrors categories above), not replace-by-snapshot — the two rules
        // seeded on every fresh install would otherwise duplicate on each restore cycle, and a
        // rule only ever added on this specific device shouldn't be wiped by a backup taken
        // elsewhere.
        val existingRules = duplicateRuleDao.observeAll().first()
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
                expensesRepo.addSpendingLimit(
                    categoryId, l.optDouble("amountHomeCurrency"), l.optString("period"),
                    createdAt = l.optLong("createdAt", System.currentTimeMillis())
                )
            }

            // Only delete rows that plausibly existed when the export was taken (createdAt <=
            // exportedAt) — anything created on this device after the backup, but before this
            // import ran, is presumed unrelated to the restore and must survive.
            preExistingLimits.filter { it.createdAt <= exportedAt }.forEach { expensesRepo.deleteSpendingLimit(it) }
        }

        var expensesCreated = 0
        if (root.has("expenses")) {
            val preExistingExpenses = expensesRepo.expensesSnapshot()

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
                val newExpenseId = expensesRepo.addExpense(
                    title = e.optStringOrNull("title"),
                    totalAmount = e.optDouble("totalAmount"),
                    currencyCode = e.optString("currencyCode"),
                    vendor = e.optStringOrNull("vendor"),
                    bank = e.optStringOrNull("bank"),
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
                    // Old pre-existing rows are deleted AFTER this insert loop (below), so they're
                    // still present here and would otherwise get misdetected as duplicates of the
                    // very rows they're about to be replaced by — import must never be blocked by
                    // this check (RecordSource.HUB_IMPORT: another install's already-validated data).
                    checkForDuplicate = false,
                    source = e.optExpenseSource(),
                    manuallyEdited = e.optBoolean("manuallyEdited", false)
                )
                expensesCreated++

                if (newExpenseId > 0) {
                    val importedAttachments = e.optJSONArray("attachments") ?: JSONArray()
                    for (j in 0 until importedAttachments.length()) {
                        val a = importedAttachments.getJSONObject(j)
                        val fileName = a.optString("fileName").takeIf { it.isNotBlank() } ?: continue
                        attachmentDao.insert(
                            AttachmentEntity(
                                recordType = ExpensesAttachments.RECORD_TYPE,
                                recordId = newExpenseId,
                                fileName = fileName,
                                source = a.optString("source", AttachmentSource.MANUAL),
                                createdAt = a.optLong("createdAt", System.currentTimeMillis()),
                                groupId = a.optStringOrNull("groupId"),
                                groupOrder = a.optInt("groupOrder", 0)
                            )
                        )
                    }
                }
            }

            preExistingExpenses.filter { it.createdAt <= exportedAt }.forEach { expensesRepo.deleteExpenseById(it.id) }
        }

        return VoxResult(
            ok = true,
            text = "$expensesCreated expenses imported, $categoriesCreated new categories " +
                "(${importedCategories.length() - categoriesCreated} matched existing), " +
                "$rulesCreated new duplicate rules (${importedRules.length() - rulesCreated} matched existing)"
        )
    }

    /** Same extraction shape as vox-commander's ModelDownloader.unzipModel() (ZipInputStream/
     *  nextEntry iteration), written fresh here since that module isn't a dependency of this one.
     *  Every entry name is flattened to its bare filename (zip-slip defense) — every entry in a
     *  receipts zip is expected to be a bare filename anyway, never a nested path. */
    private fun extractReceiptsZip(uri: Uri) {
        val receiptsDir = File(context.filesDir, "receipts").apply { mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val safeName = File(entry.name).name
                        if (safeName.isNotBlank()) {
                            FileOutputStream(File(receiptsDir, safeName)).use { fos -> zis.copyTo(fos) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
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
    JSONObject(gson.toJson(copy(appCacheJson = null, onboardingCompleted = false)))

/** Returns Room/DataStore defaults for [ExpensesSettings] if [this] isn't valid JSON for it (e.g. a
 *  corrupt/foreign import file). [paymentSourcePackages]/[bankingSourcePackages] get an extra
 *  null-coalesce afterward — Gson leaves a `Set` genuinely null (not the data class's `emptySet()`
 *  default) when an old/foreign payload is missing that key entirely, the same failure mode
 *  [com.voxapps.commander.receiver.CommanderExportHandler.parsePortableSettings] already guards
 *  against for its own collection fields. */
private fun JSONObject.toExpensesSettings(): ExpensesSettings {
    val parsed = gson.fromJson(toString(), ExpensesSettings::class.java) ?: ExpensesSettings()
    return parsed.copy(
        paymentSourcePackages = parsed.paymentSourcePackages ?: emptySet(),
        bankingSourcePackages = parsed.bankingSourcePackages ?: emptySet(),
        locationCacheTtl = parsed.locationCacheTtl ?: "ONE_DAY"
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

private fun MerchantCategoryMemory.toJson(): JSONObject = JSONObject().apply {
    put("vendorKey", vendorKey)
    put("categoryId", categoryId)
    put("consecutiveCount", consecutiveCount)
    put("updatedAt", updatedAt)
}

private fun Expense.toJson(items: List<ExpenseLineItem>, attachments: List<AttachmentEntity> = emptyList()): JSONObject = JSONObject().apply {
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
    put("direction", direction.toJsonValue())
    put("receiptImageName", receiptImageName)
    put("createdAt", createdAt)
    put("source", source.name)
    put("manuallyEdited", manuallyEdited)
    put("items", JSONArray(items.map { it.toJson() }))
    put("attachments", JSONArray(attachments.map { it.toJson() }))
}

private fun AttachmentEntity.toJson(): JSONObject = JSONObject().apply {
    put("fileName", fileName)
    put("source", source)
    put("createdAt", createdAt)
    put("groupId", groupId)
    put("groupOrder", groupOrder)
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

/** Lenient parse for a field that didn't exist before this export/import round-trip supported it —
 *  older backups (and any unrecognized/corrupt value) fall back to [ExpenseSource.MANUAL]. */
private fun JSONObject.optExpenseSource(key: String = "source"): ExpenseSource =
    optStringOrNull(key)?.let { runCatching { ExpenseSource.valueOf(it) }.getOrNull() } ?: ExpenseSource.MANUAL

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
