package com.voxapps.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
enum class ConnectionTestState {
    Idle,
    Testing,
    Online,

    /** The service was reached and did not answer as it should. Something is known about it. */
    Offline,

    /**
     * The service was never reached — its name did not resolve.
     *
     * Distinct from [Offline] because nothing was learned about the service: showing a red cross for
     * a phone whose Wi-Fi had not finished associating blames the endpoint for the device. It
     * happens on the first test after a cold start, which is exactly when a card first appears.
     */
    NoNetwork
}

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
    noNetworkLabel: String = "No connection",
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
        ConnectionTestState.NoNetwork -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = modifier.padding(vertical = 2.dp)
        ) {
            // Not a cross: nothing was found wrong with the service.
            Text(text = "\u26A0\uFE0F", style = MaterialTheme.typography.labelSmall)
            Text(
                text = noNetworkLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
 * Tests an endpoint and shows the result, re-testing whenever anything that could change the answer
 * changes.
 *
 * [keys] is everything the result depends on — the selected item, and a fingerprint of the
 * credential it uses. A credential belongs in there because entering a key is exactly when the
 * previous ❌ stops being true, and the row otherwise kept showing a failure from before the key
 * existed. Pass a *fingerprint*, never the key itself: this ends up in a composition key.
 *
 * Composition is the fourth trigger, and it comes for free: a dropdown that builds its rows when it
 * opens re-tests on opening. The tap-to-retry exists for what none of that covers — a network that
 * came back, or an endpoint that was simply down a moment ago. Without it, a target whose keys never
 * change (an extractor with no configuration) could be tested once per screen visit and never again.
 *
 * The first entry in [keys] doubles as the identity: blank or null means nothing is selected, and
 * nothing is tested.
 */
@Composable
fun ConnectionTestAuto(
    keys: List<Any?>,
    /** The test, reporting which of the three outcomes it found. A caller with only a boolean maps
     *  it: `if (ok) Online else Offline`. */
    testFn: suspend () -> ConnectionTestState,
    testingLabel: String = "Testing…",
    onlineLabel: String = "Online",
    offlineLabel: String = "Offline",
    noNetworkLabel: String = "No connection",
    retryDescription: String = "Test connection again",
    modifier: Modifier = Modifier
) {
    var retries by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(ConnectionTestState.Testing) }

    val identity = keys.firstOrNull()
    val selected = identity != null && (identity !is String || identity.isNotBlank())

    LaunchedEffect(keys, retries) {
        if (!selected) {
            state = ConnectionTestState.Idle
            return@LaunchedEffect
        }
        state = ConnectionTestState.Testing
        state = testFn()
    }

    if (!selected) return

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        ConnectionTestIndicator(state, testingLabel, onlineLabel, offlineLabel, noNetworkLabel)
        if (state != ConnectionTestState.Testing) {
            IconButton(onClick = { retries++ }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = retryDescription,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
