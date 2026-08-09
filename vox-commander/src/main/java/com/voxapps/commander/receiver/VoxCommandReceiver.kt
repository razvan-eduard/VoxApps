package com.voxapps.commander.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.backup.VoxBackupDispatch
import com.voxapps.backup.VoxImportMode
import com.voxapps.backup.VoxSnapshotReplaceImporter
import com.voxapps.commander.VoxApplication
import com.voxapps.commander.domain.media.MediaControlIpcHandler
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Commander's own entry point on the Vox command bus (mirrors vox-notes'/vox-expenses'
 * VoxCommandReceiver) — lets Vox Hub discover Commander too and export/import its settings.
 * Commander doesn't consume [VoxIpc.OP_CREATE]/[VoxIpc.OP_READ] (it's the orchestrator that sends
 * those to satellites, not a domain-owning satellite itself), so only ping/export/import are
 * handled.
 *
 * Guarded by the shared `com.voxapps.vox.permission.COMMAND` custom permission (declared once in
 * `:core:ipc`'s manifest).
 */
class VoxCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_COMMAND) return
        val command = VoxCommand.fromJson(intent.getStringExtra(VoxIpc.EXTRA_PAYLOAD)) ?: return

        val container = (context.applicationContext as VoxApplication).container

        when (command.op) {
            VoxIpc.OP_PING -> {
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = "pong").toJson(), null)
            }

            VoxIpc.OP_MEDIA_CONTROL -> {
                val result = MediaControlIpcHandler.handle(context, command.mediaAction)
                setResult(Activity.RESULT_OK, result.toJson(), null)
            }

            VoxIpc.OP_EXPORT -> {
                VoxBackupDispatch.dispatch(this) {
                    val settings = container.settingsRepository.getSettingsSnapshot()
                    val settingsJson = CommanderExportHandler.buildExportJson(
                        settings,
                        includeSecrets = command.includeSecrets,
                        searchProviderApiKeys = if (command.includeSecrets) {
                            container.settingsRepository.getAllSearchProviderApiKeys()
                        } else {
                            emptyMap()
                        },
                        credentials = container.settingsRepository.getCredentialsSnapshot()
                    )
                    val rules = container.fastMapDao.getAllRulesOnce()
                    val rulesJson = CommanderExportHandler.buildFastMapRulesJson(rules)
                    val json = JSONObject()
                        .put("settings", JSONObject(settingsJson))
                        .put("fastMapRules", JSONArray(rulesJson))
                        .toString()
                    VoxResult(ok = true, text = json)
                }
            }

            VoxIpc.OP_IMPORT -> {
                VoxBackupDispatch.dispatch(this) {
                    val root = try {
                        JSONObject(command.text.orEmpty())
                    } catch (e: Exception) {
                        null
                    }
                    if (root == null) {
                        return@dispatch VoxResult(ok = false, text = "Invalid import payload")
                    }

                    val imported = root.optJSONObject("settings")?.toString()
                        ?.let { CommanderExportHandler.parsePortableSettings(it) }
                    if (imported != null) {
                        container.settingsRepository.restoreImportedSettings(imported)
                    }

                    // A FastMapRule has no name/title field to merge by (unlike categories/layers
                    // elsewhere), so insert every imported rule (id=0, fresh local ids — cross-device
                    // ids are meaningless) and reconcile pre-existing rules per the user's chosen
                    // import mode (defaults to MERGE, but a FastMapRule has no createdAt to gate on,
                    // so MERGE here behaves the same as FULL_OVERRIDE unless ADDITIVE is chosen).
                    var rulesImported = 0
                    root.optJSONArray("fastMapRules")?.toString()?.let { rulesJson ->
                        CommanderExportHandler.parseFastMapRules(rulesJson)?.let { rules ->
                            val preExisting = container.fastMapDao.getAllRulesOnce()
                            rulesImported = VoxSnapshotReplaceImporter.restore(
                                mode = VoxImportMode.fromWireValue(command.importMode),
                                imported = rules,
                                preExisting = preExisting,
                                insert = { container.fastMapDao.insertRule(it.copy(id = 0)); 1L },
                                delete = { container.fastMapDao.deleteRule(it) }
                            )
                        }
                    }

                    if (imported != null || rulesImported > 0) {
                        VoxResult(ok = true, text = "Settings imported, $rulesImported FastMap rules imported")
                    } else {
                        VoxResult(ok = false, text = "Invalid import payload")
                    }
                }
            }
        }
    }
}
