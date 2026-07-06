package com.voxcommander.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Shared connection test status enum.
 * Used by ConnectionTestIndicator and ConnectionTestAuto.
 */
enum class ConnectionTestState { Idle, Testing, Online, Offline }

/**
 * Reusable inline status indicator — shows spinner / ✅ / ❌ with label.
 * Same visual pattern as PipedSettingsSection's status row.
 *
 * Usage: pass the current [state] and optional [testingLabel]/[onlineLabel]/[offlineLabel].
 * Or use [ConnectionTestAuto] which auto-tests and manages state.
 */
@Composable
fun ConnectionTestIndicator(
    state: ConnectionTestState,
    testingLabel: String = "Testing…",
    onlineLabel: String = "Online",
    offlineLabel: String = "Offline",
    modifier: Modifier = Modifier
) {
    when (state) {
        ConnectionTestState.Testing -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = modifier.padding(vertical = 2.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = testingLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ConnectionTestState.Online -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = modifier.padding(vertical = 2.dp)
        ) {
            Text(text = "\u2705", style = MaterialTheme.typography.labelSmall)
            Text(
                text = onlineLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        ConnectionTestState.Offline -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = modifier.padding(vertical = 2.dp)
        ) {
            Text(text = "\u274C", style = MaterialTheme.typography.labelSmall)
            Text(
                text = offlineLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        ConnectionTestState.Idle -> {}
    }
}

/**
 * Auto-testing variant: runs [testFn] immediately on first composition
 * and whenever [testKey] changes. Manages its own state.
 *
 * Same pattern as PipedSettingsSection's LaunchedEffect(pipedApiUrl).
 */
@Composable
fun ConnectionTestAuto(
    testKey: String,
    testFn: suspend () -> Boolean,
    testingLabel: String = "Testing…",
    onlineLabel: String = "Online",
    offlineLabel: String = "Offline",
    modifier: Modifier = Modifier
) {
    var state by remember(testKey) { mutableStateOf(ConnectionTestState.Testing) }
    LaunchedEffect(testKey) {
        if (testKey.isBlank()) {
            state = ConnectionTestState.Idle
            return@LaunchedEffect
        }
        state = ConnectionTestState.Testing
        val ok = testFn()
        state = if (ok) ConnectionTestState.Online else ConnectionTestState.Offline
    }
    ConnectionTestIndicator(state, testingLabel, onlineLabel, offlineLabel, modifier)
}
