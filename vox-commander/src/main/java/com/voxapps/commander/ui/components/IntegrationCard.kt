package com.voxapps.commander.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.registry.ApiIntegration
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.service.ProbeSpec
import com.voxapps.commander.domain.service.ServiceProbe
import com.voxapps.commander.service.OAuth2Manager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One declared API integration: what it is, whether it is connected, and whether that is still true.
 *
 * Written once, for whatever `api_integrations.json` declares. The screen this replaces had a single
 * hand-written Spotify card — its title, its status strings and its client-id flow were all
 * Spotify's, and the registry exposed no way to enumerate anything else, so a second integration in
 * the file rendered nowhere at all.
 *
 * "Connected" also meant something weaker than it looked: a token present in local storage. A
 * revoked or expired one still showed green. Here the state comes from the token *and* from asking
 * the service, which is what [probeSpec] is for.
 */
@Composable
fun IntegrationCard(
    integration: ApiIntegration,
    settingsRepo: SettingsRepository,
    languageManager: LanguageManager,
    context: Context,
    onConnected: (clientId: String) -> Unit = {},
    onDisconnected: () -> Unit = {}
) {
    val auth = integration.auth ?: return
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(OAuth2Manager.isAuthorized(integration.id)) }
    var clientId by remember { mutableStateOf(settingsRepo.getServiceClientIdSync(integration.id).orEmpty()) }
    var isConnecting by remember { mutableStateOf(false) }
    var showDisconnect by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }

    fun authorise(withClientId: String) {
        val config = auth.toOAuthConfig(integration.id) ?: run {
            connectError = languageManager.getString("spotify_connect_failed")
            return
        }
        isConnecting = true
        connectError = null
        OAuth2Manager.startAuthFlow(context, config, withClientId) { ok, errorMsg ->
            isConnecting = false
            connected = ok
            if (ok) onConnected(withClientId)
            else connectError = when (errorMsg) {
                "no_internet" -> languageManager.getString("offline_banner")
                "access_denied" -> languageManager.getString("spotify_error_auth_required")
                else -> languageManager.getString("spotify_connect_failed")
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = integration.label.ifBlank { integration.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // What the service says, not what local storage remembers. Null when the integration
            // declares no endpoint to ask, in which case nothing is shown rather than a guess.
            val accessToken = settingsRepo.getServiceAccessTokenSync(integration.id)
            val probeSpec = ProbeSpec.from(
                id = integration.id,
                endpoint = integration.serviceUrl,
                probeUrl = integration.probeUrl,
                auth = auth.probeStyle(),
                credential = accessToken
            )
            if (probeSpec != null) {
                ConnectionTestCard(
                    keys = listOf(integration.id, connected, accessToken?.length ?: 0),
                    testFn = { probeWithRefresh(probeSpec, integration, clientId, settingsRepo) },
                    tokenState = TokenState(
                        present = !accessToken.isNullOrBlank(),
                        expiresAtMillis = settingsRepo.getServiceTokenExpirySync(integration.id)
                    ),
                    onlineLabel = languageManager.getString("spotify_connected"),
                    offlineLabel = languageManager.getString("spotify_disconnected")
                )
            }

            if (auth.requiresClientId && !connected && clientId.isNotBlank()) {
                TextButton(onClick = { showSetup = true }) {
                    Text(
                        text = languageManager.getString("spotify_change_client_id"),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            connectError?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (connected) {
                Button(
                    onClick = { showDisconnect = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(languageManager.getString("spotify_connected"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        // A client id is asked for only by services that declare they need one.
                        if (!auth.requiresClientId || clientId.isNotBlank()) authorise(clientId)
                        else showSetup = true
                    },
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(languageManager.getString("spotify_connect"), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDisconnect) {
        AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text(languageManager.getString("spotify_disconnect_title")) },
            text = { Text(languageManager.getString("spotify_disconnect_message")) },
            confirmButton = {
                TextButton(onClick = {
                    OAuth2Manager.logout(integration.id)
                    onDisconnected()
                    connected = false
                    showDisconnect = false
                }) {
                    Text(languageManager.getString("spotify_disconnect_confirm"), color = Color(0xFFF44336))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnect = false }) {
                    Text(languageManager.getString("cancel_button"))
                }
            }
        )
    }

    if (showSetup) {
        ClientIdDialog(
            integration = integration,
            languageManager = languageManager,
            initialClientId = clientId,
            onDismiss = { showSetup = false },
            onConfirm = { entered ->
                showSetup = false
                clientId = entered
                scope.launch {
                    settingsRepo.setServiceClientId(integration.id, entered)
                    onConnected(entered)
                    authorise(entered)
                }
            }
        )
    }
}

/**
 * Probes with the stored token and, if the service rejects it, refreshes once and asks again.
 *
 * An access token expiring is ordinary — that is what refresh tokens are for — so a single 401 is
 * not yet an answer. What makes it worth distinguishing is that the two failures need opposite
 * things from the user: a refreshable token needs nothing at all, a revoked one needs
 * re-authorising. Only one retry, and only on a rejection: a network failure must not trigger a
 * token exchange.
 */
private suspend fun probeWithRefresh(
    spec: ProbeSpec,
    integration: ApiIntegration,
    clientId: String,
    settingsRepo: SettingsRepository
): Boolean {
    val first = ServiceProbe.detailed(spec, settingsRepo)
    if (first.ok || !first.rejected) return first.ok

    val config = integration.auth?.toOAuthConfig(integration.id) ?: return false
    if (clientId.isBlank() || settingsRepo.getServiceRefreshTokenSync(integration.id).isNullOrBlank()) return false

    // Two things this call gets right that doing it by hand did not.
    //
    // It blocks on network I/O and picks no thread of its own, so it runs on the caller's — which
    // here is the composition's, i.e. Main. That threw NetworkOnMainThreadException, whose message
    // is null, so the log read "token refresh exception: null" and named nothing.
    //
    // And it returns the token from memory. Persistence is fire-and-forget (`AppScope.io.launch`),
    // so reading the store straight after a refresh hands back the *previous* token — which is
    // rejected exactly as the expired one was, making a successful refresh look like a failed one.
    val token = withContext(Dispatchers.IO) { OAuth2Manager.getValidAccessToken(config, clientId) }
        ?: return false

    return ServiceProbe.run(spec.copy(credential = token), settingsRepo)
}

/**
 * Asks for the client id a service's OAuth application issues.
 *
 * Everything here is declared: the portal to create the application at, the setup steps (as
 * translation keys, so they stay translated), and the redirect URI to paste — which comes from the
 * declaration rather than being written out again, as it was, in a string the dialog could not
 * verify.
 */
@Composable
private fun ClientIdDialog(
    integration: ApiIntegration,
    languageManager: LanguageManager,
    initialClientId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val auth = integration.auth ?: return
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf(initialClientId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("spotify_setup_title")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                auth.clientIdUrl?.toUri()?.host?.let { host ->
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { uriHandler.openUri(auth.clientIdUrl) }
                    )
                }

                auth.setupHelpKeys.forEach { key ->
                    Text(languageManager.getString(key), style = MaterialTheme.typography.bodyMedium)
                }

                auth.redirectUri?.let { redirect ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { clipboard.setText(AnnotatedString(redirect)) }
                    ) {
                        SelectionContainer {
                            Text(
                                text = redirect,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(languageManager.getString("spotify_client_id_label")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input.trim()) }, enabled = input.isNotBlank()) {
                Text(languageManager.getString("save_button"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel_button")) }
        }
    )
}
