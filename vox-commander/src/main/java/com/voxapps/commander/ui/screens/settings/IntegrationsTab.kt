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
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.service.SpotifyPkceManager
import com.voxapps.commander.service.SpotifyRemoteManager
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
    var spotifyConnected by remember { mutableStateOf(SpotifyPkceManager.isAuthorized) }
    var spotifyClientId by remember { mutableStateOf(settingsRepo.getSpotifyClientIdSync() ?: "") }
    var isConnecting by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showSetupDialog by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = languageManager.getString("integrations_description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // --- Spotify Integration Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: icon + name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1DB954)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Spotify",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = languageManager.getString("spotify_integration_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (spotifyConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
                    )
                    Text(
                        text = if (spotifyConnected) languageManager.getString("spotify_connected")
                               else languageManager.getString("spotify_disconnected"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (spotifyConnected) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }

                // Change Client ID button (when not connected and Client ID already set)
                if (!spotifyConnected && spotifyClientId.isNotBlank()) {
                    TextButton(
                        onClick = { showSetupDialog = true },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Text(
                            text = languageManager.getString("spotify_change_client_id"),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Error message
                 connectError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Connect / Disconnect button
                if (spotifyConnected) {
                    // Green "Connected" button → opens disconnect dialog
                    Button(
                        onClick = { showDisconnectDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Text(
                            text = languageManager.getString("spotify_connected"),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // Red "Connect" button → opens setup dialog
                    Button(
                        onClick = {
                            if (spotifyClientId.isNotBlank()) {
                                // Already has Client ID, start PKCE OAuth flow
                                isConnecting = true
                                connectError = null
                                SpotifyPkceManager.startAuthFlow(context, spotifyClientId) { ok, errorMsg ->
                                    isConnecting = false
                                    spotifyConnected = ok
                                    if (!ok) {
                                        connectError = when (errorMsg) {
                                            "no_internet" -> languageManager.getString("offline_banner")
                                            "access_denied" -> languageManager.getString("spotify_error_auth_required")
                                            "token_exchange_failed" -> languageManager.getString("spotify_connect_failed")
                                            else -> languageManager.getString("spotify_connect_failed")
                                        }
                                    }
                                }
                            } else {
                                // No Client ID → show setup dialog
                                showSetupDialog = true
                            }
                        },
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336)
                        )
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(
                                text = languageManager.getString("spotify_connect"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Disconnect confirmation dialog
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(languageManager.getString("spotify_disconnect_title")) },
            text = { Text(languageManager.getString("spotify_disconnect_message")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SpotifyPkceManager.logout()
                        SpotifyRemoteManager.disconnect()
                        spotifyConnected = false
                        showDisconnectDialog = false
                    }
                ) {
                    Text(languageManager.getString("spotify_disconnect_confirm"), color = Color(0xFFF44336))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(languageManager.getString("cancel_button"))
                }
            }
        )
    }

    // Spotify setup dialog with instructions + Client ID input
    if (showSetupDialog) {
        SpotifySetupDialog(

            onDismiss = {
                showSetupDialog = false
            },
            onConfirm = { clientId ->
                showSetupDialog = false
                spotifyClientId = clientId
                scope.launch {
                    settingsRepo.setSpotifyClientId(clientId)
                    SpotifyRemoteManager.setClientId(clientId)
                    isConnecting = true
                    connectError = null
                    SpotifyPkceManager.startAuthFlow(context, clientId) { ok, errorMsg ->
                        isConnecting = false
                        spotifyConnected = ok
                        if (!ok) {
                            connectError = when (errorMsg) {
                                "no_internet" -> languageManager.getString("offline_banner")
                                "access_denied" -> languageManager.getString("spotify_error_auth_required")
                                "token_exchange_failed" -> languageManager.getString("spotify_connect_failed")
                                else -> languageManager.getString("spotify_connect_failed")
                            }
                        }
                    }
                }
            },
            initialClientId = spotifyClientId
        )
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
