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
import com.voxapps.hub.domain.sync.PairedPeer
import com.voxapps.hub.domain.sync.SyncPeerStore
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import org.json.JSONArray
import org.json.JSONObject

private data class AppScopeOptions(val packageName: String, val label: String, val availableNames: List<String>)

/**
 * Per-peer category/layer checklist — the "poate nu vrem toate tipurile de expenses, sau nu toate
 * calendarele" scope selection. Reuses [VoxIpc.OP_EXPORT] (scope=DATA) purely to read each app's
 * category/layer *names* rather than adding a dedicated lightweight IPC op for it — heavier than
 * strictly necessary, but this screen is opened rarely (once per peer, when the user wants to narrow
 * what syncs), unlike [com.voxapps.hub.domain.sync.SyncOrchestrator]'s own OP_SYNC_EXPORT calls which
 * run every session and stay cheap via the `since` watermark.
 *
 * Absence of a package's key in [PairedPeer.scopeNamesByApp] means "sync everything" (matches every
 * satellite's own `scopeNames == null` convention) — this screen represents that as every checkbox
 * checked, and removes the map entry again once the user re-checks the last excluded item.
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
            val names = if (export?.ok == true) extractScopeNames(export.text) else emptyList()
            AppScopeOptions(app.packageName, app.label, names)
        }
        loading = false
    }

    fun toggle(options: AppScopeOptions, name: String, checked: Boolean) {
        val currentSelection = currentPeer.scopeNamesByApp[options.packageName] ?: options.availableNames
        val updatedSelection = if (checked) (currentSelection + name).distinct() else currentSelection - name
        val newScopeMap = currentPeer.scopeNamesByApp.toMutableMap()
        if (updatedSelection.size >= options.availableNames.size) {
            newScopeMap.remove(options.packageName)
        } else {
            newScopeMap[options.packageName] = updatedSelection
        }
        val updatedPeer = currentPeer.copy(scopeNamesByApp = newScopeMap)
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
            appOptions.forEach { options ->
                item(key = "${options.packageName}_header") {
                    Text(
                        options.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                    HorizontalDivider()
                }
                if (options.availableNames.isEmpty()) {
                    item(key = "${options.packageName}_empty") {
                        Text(
                            languageManager.getString("sync_scope_no_categories"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(options.availableNames, key = { "${options.packageName}_$it" }) { name ->
                        val checked = currentPeer.scopeNamesByApp[options.packageName]?.contains(name) ?: true
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle(options, name, it) })
                            Text(name)
                        }
                    }
                }
            }
        }
    }
}

private fun extractScopeNames(exportJson: String): List<String> {
    val root = try {
        JSONObject(exportJson)
    } catch (e: Exception) {
        return emptyList()
    }
    val array: JSONArray = root.optJSONArray("categories") ?: root.optJSONArray("layers") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
    }
}
