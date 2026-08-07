package com.voxapps.backup

import android.content.Context
import android.net.Uri
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.logging.Logger
import org.json.JSONObject

/**
 * The shared skeleton of every satellite's Hub export/import, with the app-specific middle left to
 * subclasses.
 *
 * `:core:backup` had already extracted the *leaves* of this operation — [VoxBiometricGate],
 * [VoxSettingsRoundTrip], [VoxSnapshotReplaceImporter], [VoxAttachmentZipUtil] — but never the order
 * they run in, so each app re-implemented the same sequence: gate, build the envelope, honour the
 * export scope, bundle photos; and on the way back: parse, restore settings, read `exported_at`,
 * unpack photos, merge, summarise. Three copies of a sequence is three places for a step to go
 * missing, and that had already happened once: Calendar's import was for a time missing the
 * `exported_at` gate the other two had, so every import wiped pre-existing entries regardless of
 * when they were created. It was fixed in place, in the copy — which is the failure mode this class
 * removes, since a step that lives here can't be omitted by an app that never mentions it.
 *
 * Notes and Calendar extend this. Expenses deliberately does not: its envelope carries an opt-in
 * secret and *two* attachment archives on separate [VoxResult] fields, so it would need hooks no
 * other app uses. It shares the leaf helpers ([mergeByName], [optStringOrNull]) instead.
 *
 * Subclasses supply data, not control flow: [exportData] fills in the payload and reports which
 * attachment files it referenced, [importData] consumes the payload and returns the user-facing
 * summary. Neither sees the lock gate, the scope flags, or the zip handling.
 */
abstract class VoxExportImportHandler(
    private val context: Context,
    private val attachmentsDir: String,
    private val fileProviderAuthority: String
) {
    /** Shown when the app is biometric-locked; each app words this itself (its ReadResponder owns
     *  the string, so export/import and reads stay consistent with each other). */
    protected abstract val lockedMessage: String

    /** Whether the biometric gate is currently closed — normally [VoxBiometricGate.isLocked] against
     *  this app's own settings snapshot and session manager. */
    protected abstract suspend fun isLocked(): Boolean

    /** This app's settings as the `"settings"` object of the envelope. */
    protected abstract suspend fun exportSettings(): JSONObject

    /** Applies a previously exported `"settings"` object. */
    protected abstract suspend fun restoreSettings(settings: JSONObject)

    /**
     * Writes this app's records into [json].
     *
     * @return every attachment file name referenced by what was written, so the base class can
     *   bundle exactly those into the photo zip. Returning the names rather than building the zip
     *   here is what keeps `includePhotos` out of the subclass.
     */
    protected abstract suspend fun exportData(json: JSONObject): List<String>

    /**
     * Restores this app's records from [root].
     *
     * @param exportedAt when the backup was taken, already defaulted (see [import]). Records created
     *   after this instant are not part of the snapshot being restored and must survive it.
     * @return the user-facing summary line for [VoxResult.text].
     */
    protected abstract suspend fun importData(root: JSONObject, exportedAt: Long, mode: VoxImportMode): String

    suspend fun export(scope: String = VoxIpc.EXPORT_SCOPE_BOTH, includePhotos: Boolean = false): VoxResult {
        if (isLocked()) return VoxResult(ok = false, text = lockedMessage)

        val json = JSONObject()
        var attachmentUri: String? = null
        if (scope != VoxIpc.EXPORT_SCOPE_DATA) {
            json.put(KEY_SETTINGS, exportSettings())
        }
        if (scope != VoxIpc.EXPORT_SCOPE_SETTINGS) {
            val fileNames = exportData(json)
            if (includePhotos) {
                attachmentUri = VoxAttachmentZipUtil
                    .build(context, attachmentsDir, fileNames, fileProviderAuthority)
                    ?.toString()
            }
        }
        return VoxResult(ok = true, text = json.toString(), attachmentUri = attachmentUri)
    }

    suspend fun import(payloadJson: String, importMode: VoxImportMode = VoxImportMode.MERGE): VoxResult {
        if (isLocked()) return VoxResult(ok = false, text = lockedMessage)

        val root = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return VoxResult(ok = false, text = INVALID_PAYLOAD_MESSAGE)
        }

        root.optJSONObject(KEY_SETTINGS)?.let { restoreSettings(it) }

        // Injected by Hub's ExportImportUtil.parseImportDocument() from the outer export document's
        // timestamp. Defaults to 0L — never true against any real createdAt — so a payload missing
        // the field fails safe by deleting nothing, rather than reverting to "delete everything that
        // existed at import time".
        val exportedAt = root.optLong(KEY_EXPORTED_AT, 0L)

        // Staged before importData() runs, so records that reference a photo by file name resolve
        // their thumbnails as soon as the import completes. Best-effort: a failure here leaves the
        // photos missing but never fails the rest of the import.
        root.optStringOrNull(KEY_ATTACHMENTS_ZIP_URI)?.let { uriString ->
            try {
                VoxAttachmentZipUtil.extract(context, attachmentsDir, Uri.parse(uriString))
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to import attachment photos from $uriString — continuing without them", e)
            }
        }

        return VoxResult(ok = true, text = importData(root, exportedAt, importMode))
    }

    companion object {
        private const val TAG = "VoxExportImportHandler"

        const val INVALID_PAYLOAD_MESSAGE = "Invalid import payload"

        const val KEY_SETTINGS = "settings"
        const val KEY_EXPORTED_AT = "exported_at"
        const val KEY_ATTACHMENTS_ZIP_URI = "attachmentsZipUri"
    }
}
