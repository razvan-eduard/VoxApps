package com.voxapps.hub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.datahygiene.SyncDeltaKeys
import com.voxapps.hub.domain.sync.PairedPeer
import com.voxapps.hub.domain.sync.SyncPeerStore
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import org.json.JSONArray
import org.json.JSONObject

/** One tickable container: [value] is what travels on the wire and sits in
 *  [PairedPeer.scopeNamesByApp]; [label] is what the row shows — the two differ only for synthetic
 *  rows like the no-account ("cash") one, whose wire value must survive a UI language change. */
private data class ScopeOption(val value: String, val label: String)

private data class AppScopeOptions(val packageName: String, val label: String, val options: List<ScopeOption>)

/**
 * Per-peer shared-containers checklist — which bank accounts (Expenses), categories (Notes), and
 * calendars (Calendar) this phone shares with that device. Sharing is OPT-IN: nothing is ticked
 * until the user ticks it, an unticked app shares nothing, and containers created later stay
 * private until ticked here — so the stored selection is always the explicit list of what IS
 * shared, and "no entry" simply means an empty one (the orchestrator sends an empty scope either
 * way). Ticks only govern the continuous share; records pushed by hand from an app's multi-select
 * travel regardless.
 *
 * Reuses [VoxIpc.OP_EXPORT] (scope=DATA) purely to read each app's container names rather than
 * adding a dedicated lightweight IPC op for it — heavier than strictly necessary, but this screen
 * is opened rarely, unlike SyncOrchestrator's own OP_SYNC_EXPORT calls which run every session and
 * stay cheap via the `since` watermark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScopeScreen(
    peer: PairedPeer,
    peerStore: SyncPeerStore,
    onBack: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current

    var currentPeer by remember { mutableStateOf(peer) }
    var appOptions by remember { mutableStateOf<List<AppScopeOptions>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(peer.peerId) {
        val apps = VoxAppsDiscovery.discover(context)
            .filter { VoxIpc.OP_SYNC_EXPORT in it.actions && VoxIpc.OP_SYNC_MERGE in it.actions }
        appOptions = apps.map { app ->
            val export = VoxDataTransferClient.requestExport(context, app.packageName, VoxIpc.EXPORT_SCOPE_DATA)
            val options = if (export?.ok == true) {
                extractScopeOptions(export.text, languageManager.getString("sync_scope_cash_row"))
            } else {
                emptyList()
            }
            AppScopeOptions(app.packageName, app.label, options)
        }
        loading = false
    }

    fun toggle(options: AppScopeOptions, value: String, checked: Boolean) {
        val currentSelection = currentPeer.scopeNamesByApp[options.packageName].orEmpty()
        val updatedSelection = if (checked) (currentSelection + value).distinct() else currentSelection - value
        val updatedPeer = currentPeer.copy(
            scopeNamesByApp = currentPeer.scopeNamesByApp + (options.packageName to updatedSelection)
        )
        peerStore.upsertPeer(updatedPeer)
        currentPeer = updatedPeer
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(String.format(languageManager.getString("sync_scope_title"), peer.label)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = languageManager.getString("back"))
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item(key = "opt_in_hint") {
                Text(
                    languageManager.getString("sync_scope_opt_in_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            appOptions.forEach { options ->
                item(key = "${options.packageName}_header") {
                    Text(
                        options.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                    HorizontalDivider()
                }
                if (options.options.isEmpty()) {
                    item(key = "${options.packageName}_empty") {
                        Text(
                            languageManager.getString("sync_scope_no_categories"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(options.options, key = { "${options.packageName}_${it.value}" }) { option ->
                        val checked = currentPeer.scopeNamesByApp[options.packageName]
                            ?.contains(option.value) == true
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle(options, option.value, it) })
                            Text(option.label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The tickable container names inside one app's DATA export: bank accounts for Expenses (root rows
 * only — a card shares through the account it hangs under — named the way the records name them,
 * bank name first, plus the synthetic no-account row for cash spending), categories for Notes,
 * calendars ("layers") for Calendar.
 */
private fun extractScopeOptions(exportJson: String, cashLabel: String): List<ScopeOption> {
    val root = try {
        JSONObject(exportJson)
    } catch (e: Exception) {
        return emptyList()
    }
    root.optJSONArray("bankAccounts")?.let { accounts ->
        val names = (0 until accounts.length()).mapNotNull { i ->
            val account = accounts.optJSONObject(i) ?: return@mapNotNull null
            val isRoot = !account.has("parentId") || account.isNull("parentId")
            if (!isRoot || account.optBoolean("archived", false)) return@mapNotNull null
            account.optString("bankName").takeIf { it.isNotBlank() }
                ?: account.optString("label").takeIf { it.isNotBlank() }
        }.distinct()
        return names.map { ScopeOption(it, it) } + ScopeOption(SyncDeltaKeys.SCOPE_NO_ACCOUNT, cashLabel)
    }
    val array: JSONArray = root.optJSONArray("categories") ?: root.optJSONArray("layers") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
    }.distinct().map { ScopeOption(it, it) }
}
