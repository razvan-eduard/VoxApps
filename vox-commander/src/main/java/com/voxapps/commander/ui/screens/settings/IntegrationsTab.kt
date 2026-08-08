package com.voxapps.commander.ui.screens.settings

import com.voxapps.commander.ui.LocalLanguageManager

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Apps
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxIpc
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.intent.registry.ApiIntegrationRegistry
import com.voxapps.commander.ui.components.IntegrationCard
import com.voxapps.commander.service.OAuth2Manager
import com.voxapps.commander.service.OAuthConfig
import com.voxapps.commander.service.SpotifyRemoteManager
import com.voxapps.commander.utils.PackageNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsTab(

    settingsRepo: SettingsRepository
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Every declared integration, rendered by one card. This screen used to hold a single
    // hand-written Spotify card and ask the registry for that one entry by package name, so a
    // second integration in api_integrations.json appeared nowhere at all.
    val integrations = remember { ApiIntegrationRegistry.all() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = languageManager.getString("integrations_description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // --- Vox Apps (contract-implementing satellites) ---
        VoxAppsSection(languageManager)

        integrations.forEach { integration ->
            IntegrationCard(
                integration = integration,
                settingsRepo = settingsRepo,
                languageManager = languageManager,
                context = context,
                // The only per-service code left: a compiled SDK that has to be told the same
                // client id, and torn down on disconnect. Keyed by the declaration's own id.
                onConnected = { clientId ->
                    if (integration.id == SPOTIFY_SERVICE_ID) SpotifyRemoteManager.setClientId(clientId)
                },
                onDisconnected = {
                    if (integration.id == SPOTIFY_SERVICE_ID) SpotifyRemoteManager.disconnect()
                }
            )
        }
    }
}

private const val SPOTIFY_SERVICE_ID = "spotify"

@Composable
private fun VoxAppsSection(languageManager: LanguageManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps by com.voxapps.commander.domain.integration.VoxSatelliteRegistry.apps.collectAsStateWithLifecycle()
    // packageName -> "testing" | "ok" | "fail"
    val pingStatus = remember { mutableStateMapOf<String, String>() }
    // packageName -> "refreshing" | "ok" | "fail" for the schema cache Refresh button.
    val schemaStatus = remember { mutableStateMapOf<String, String>() }
    val schemaCache by com.voxapps.commander.domain.integration.VoxSatelliteRegistry.schemaCache.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { com.voxapps.commander.domain.integration.VoxSatelliteRegistry.refresh(context) }
        // Auto-run the contract test so only apps that actually respond show as verified.
        com.voxapps.commander.domain.integration.VoxSatelliteRegistry.apps.value.forEach { app ->
            pingStatus[app.packageName] = "testing"
            pingStatus[app.packageName] = if (VoxAppsDiscovery.ping(context, app.packageName)) "ok" else "fail"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                }
                Column {
                    Text("Vox Apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = languageManager.getString("vox_apps_desc") ?: "Installed apps that implement the Vox contract",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (apps.isEmpty()) {
                Text(
                    text = languageManager.getString("vox_apps_none") ?: "No Vox apps found. Install a satellite app (e.g. Vox Notes) to see it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                apps.forEach { app ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val status = pingStatus[app.packageName]
                        // Only apps that actually advertise get_schema support (Expenses/Calendar/
                        // Notes) get the Refresh button and schema status line — Vision, e.g., only
                        // advertises "ping" (it's an OCR producer, not a satellite), so it has nothing
                        // to fetch here and showing a failing Refresh button for it is misleading.
                        val supportsSchema = app.actions.contains(VoxIpc.OP_GET_SCHEMA)
                        Box(
                            modifier = Modifier.size(10.dp).clip(CircleShape).background(
                                when (status) {
                                    "ok" -> Color(0xFF4CAF50)
                                    "fail" -> Color(0xFFF44336)
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                val partyLabel = if (app.isFirstParty)
                                    (languageManager.getString("vox_apps_first_party") ?: "First-party")
                                else (languageManager.getString("vox_apps_third_party") ?: "Third-party")
                                Surface(
                                    color = if (app.isFirstParty) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        partyLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = if (app.isFirstParty) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val sub = app.domain?.let { d ->
                                if (app.actions.isNotEmpty()) "$d • ${app.actions.joinToString(", ")}" else d
                            } ?: app.packageName
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val statusLabel = when (status) {
                                "ok" -> languageManager.getString("vox_apps_verified") ?: "Contract verified"
                                "fail" -> languageManager.getString("vox_apps_unverified") ?: "Not responding"
                                "testing" -> languageManager.getString("vox_apps_testing") ?: "Testing…"
                                else -> null
                            }
                            statusLabel?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (status) {
                                        "ok" -> Color(0xFF2E7D32)
                                        "fail" -> Color(0xFFC62828)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            // Debugging aid only (per the plan: informational, never drives
                            // invalidation) — shows whether a cached extraction contract exists and
                            // its KSP-generated schema version.
                            val cached = schemaCache[app.packageName]
                            val schemaLabel = if (!supportsSchema) null else when (schemaStatus[app.packageName]) {
                                "refreshing" -> languageManager.getString("vox_apps_schema_refreshing") ?: "Refreshing schema…"
                                "fail" -> languageManager.getString("vox_apps_schema_fail") ?: "Schema refresh failed"
                                else -> cached?.let {
                                    if (it.needsExtractionPass) "schema v${it.fieldSchemaVersion} • 2-pass" else "schema cached • 1-pass"
                                }
                            }
                            schemaLabel?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (status == "testing") {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = {
                                pingStatus[app.packageName] = "testing"
                                scope.launch {
                                    val ok = VoxAppsDiscovery.ping(context, app.packageName)
                                    pingStatus[app.packageName] = if (ok) "ok" else "fail"
                                }
                            }) {
                                Text(languageManager.getString("vox_apps_test") ?: "Test")
                            }
                        }
                        if (supportsSchema) {
                            if (schemaStatus[app.packageName] == "refreshing") {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = {
                                    schemaStatus[app.packageName] = "refreshing"
                                    scope.launch {
                                        val schema = com.voxapps.commander.domain.integration.VoxSatelliteRegistry
                                            .refreshSchema(context, app.packageName)
                                        schemaStatus[app.packageName] = if (schema != null) "ok" else "fail"
                                    }
                                }) {
                                    Text(languageManager.getString("vox_apps_refresh") ?: "Refresh")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifySetupDialog(

    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialClientId: String = ""
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var clientIdInput by remember { mutableStateOf(initialClientId) }
    var copiedToClipboard by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("spotify_setup_title")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Step 1
                Text(
                    text = languageManager.getString("spotify_setup_step1"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard"))
                        context.startActivity(intent)
                    }
                ) {
                    Text(
                        text = "developer.spotify.com/dashboard",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    )
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Step 2
                Text(
                    text = languageManager.getString("spotify_setup_step2"),
                    style = MaterialTheme.typography.bodyMedium
                )

                // Step 3
                Text(
                    text = languageManager.getString("spotify_setup_step3"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString("voxcommander://spotify/callback"))
                            copiedToClipboard = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "voxcommander://spotify/callback",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (copiedToClipboard) {
                    Text(
                        text = languageManager.getString("spotify_setup_copied"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Step 4
                Text(
                    text = languageManager.getString("spotify_setup_step4"),
                    style = MaterialTheme.typography.bodyMedium
                )

                // Step 5 - APIs
                Text(
                    text = languageManager.getString("spotify_setup_step_apis"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                // Step 6 - User Management
                Text(
                    text = languageManager.getString("spotify_setup_step_user_mgmt"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                // Step 7 - Fingerprint & Package
                Text(
                    text = languageManager.getString("spotify_setup_step_fingerprint"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = languageManager.getString("spotify_setup_step_package_name"),
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString("com.voxapps.commander"))
                            copiedToClipboard = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "com.voxapps.commander",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = languageManager.getString("spotify_setup_step_fingerprint_sha1"),
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString("EC:4F:84:B2:A5:3B:E0:51:43:4D:5E:12:9A:C7:DC:2B:60:FC:46:CE"))
                            copiedToClipboard = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "EC:4F:84:B2:A5:3B:E0:51:43:4D:5E:12:9A:C7:DC:2B:60:FC:46:CE",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Client ID input
                OutlinedTextField(
                    value = clientIdInput,
                    onValueChange = { clientIdInput = it },
                    label = { Text(languageManager.getString("spotify_client_id")) },
                    placeholder = { Text(languageManager.getString("spotify_client_id_placeholder")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(clientIdInput.trim()) },
                enabled = clientIdInput.isNotBlank()
            ) {
                Text(languageManager.getString("spotify_setup_connect"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(languageManager.getString("cancel_button"))
            }
        }
    )
}
