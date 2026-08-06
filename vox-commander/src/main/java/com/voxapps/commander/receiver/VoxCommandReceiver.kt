package com.voxapps.commander.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.commander.VoxApplication
import com.voxapps.commander.domain.media.MediaControlIpcHandler
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = container.settingsRepository.getSettingsSnapshot()
                        val settingsJson = CommanderExportHandler.buildExportJson(
                            settings,
                            includeSecrets = command.includeSecrets,
                            searchProviderApiKeys = if (command.includeSecrets) {
                                container.settingsRepository.getAllSearchProviderApiKeys()
                            } else {
                                emptyMap()
                            }
                        )
                        val rules = container.fastMapDao.getAllRulesOnce()
                        val rulesJson = CommanderExportHandler.buildFastMapRulesJson(rules)
                        val json = JSONObject()
                            .put("settings", JSONObject(settingsJson))
                            .put("fastMapRules", JSONArray(rulesJson))
                            .toString()
                        // Must use the PendingResult's own setResultData, not the inherited
                        // BroadcastReceiver.setResult() — the latter throws "Call while result is
                        // not pending" once called from outside onReceive()'s synchronous window,
                        // which goAsync()'s whole point is to let us do.
                        pending.setResultData(VoxResult(ok = true, text = json).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_IMPORT -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val root = try {
                            JSONObject(command.text.orEmpty())
                        } catch (e: Exception) {
                            null
                        }
                        if (root == null) {
                            pending.setResultData(VoxResult(ok = false, text = "Invalid import payload").toJson())
                            return@launch
                        }

                        val imported = root.optJSONObject("settings")?.toString()
                            ?.let { CommanderExportHandler.parsePortableSettings(it) }
                        if (imported != null) {
                            container.settingsRepository.restoreImportedSettings(imported)
                        }

                        // Full replace, not merge: a FastMapRule has no name/title field to merge
                        // by (unlike categories/layers elsewhere), so a restore snapshots the
                        // existing rules, inserts every imported one (id=0, fresh local ids —
                        // cross-device ids are meaningless), then deletes the old snapshot.
                        var rulesImported = 0
                        root.optJSONArray("fastMapRules")?.toString()?.let { rulesJson ->
                            CommanderExportHandler.parseFastMapRules(rulesJson)?.let { rules ->
                                val preExisting = container.fastMapDao.getAllRulesOnce()
                                rules.forEach { container.fastMapDao.insertRule(it.copy(id = 0)) }
                                preExisting.forEach { container.fastMapDao.deleteRule(it) }
                                rulesImported = rules.size
                            }
                        }

                        val result = if (imported != null || rulesImported > 0) {
                            VoxResult(ok = true, text = "Settings imported, $rulesImported FastMap rules imported")
                        } else {
                            VoxResult(ok = false, text = "Invalid import payload")
                        }
                        pending.setResultData(result.toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
