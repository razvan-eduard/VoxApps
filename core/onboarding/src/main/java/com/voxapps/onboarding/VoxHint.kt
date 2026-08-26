package com.voxapps.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What a screen says the first time somebody arrives on it, and stops saying once they have read it.
 *
 * A settings page can explain itself in a paragraph nobody needs twice. Left permanently on the page
 * it becomes furniture people read past; shown as a dialog and never again, it is read once and
 * gone. So the text lives here rather than in the page, and the answer to "have they seen it" lives
 * in one store rather than in a boolean per screen.
 *
 * Dismissing without ticking the box is not an answer: the dialog returns next time, which is what
 * makes "don't show again" mean something a person chose rather than something they clicked past.
 */
class VoxHintStore(private val dataStore: DataStore<Preferences>) {

    /**
     * Whether [key] has been silenced.
     *
     * Read as a flow so a screen that is open when the tutorial is replayed starts offering its hint
     * again without being revisited — replaying is a decision about the whole app, and a screen
     * hiding behind a stale read would be the one place it did not take.
     */
    fun dismissed(key: String): Flow<Boolean> =
        dataStore.data.map { it[booleanPreferencesKey(prefix + key)] ?: false }

    suspend fun isDismissed(key: String): Boolean = dismissed(key).first()

    suspend fun dismiss(key: String) {
        dataStore.edit { it[booleanPreferencesKey(prefix + key)] = true }
    }

    /**
     * Every hint speaks again.
     *
     * What "replay the tutorial" means, beyond the tour itself: somebody asking to be shown the app
     * again is asking for all of it, and a tour that replayed while every page stayed silent would
     * be half an answer. See [VoxHintKeys] for why the keys share a prefix.
     */
    suspend fun resetAll() {
        dataStore.edit { prefs ->
            val ours = prefs.asMap().keys.filter { it.name.startsWith(prefix) }
            for (k in ours) prefs.remove(booleanPreferencesKey(k.name))
        }
    }

    private companion object {
        /** One prefix, so [resetAll] can find them all without being told what exists. */
        const val prefix = "hint_"
    }
}

/** The screens that explain themselves. Named here so an app and its settings agree on the string. */
object VoxHintKeys {
    const val NOTIFICATION_CAPTURE = "notification_capture"
    const val CLEANUP_RULES = "cleanup_rules"
    const val CATEGORIES = "categories"
    const val BANK_ACCOUNTS = "bank_accounts"
    const val RECURRING = "recurring"
    const val BACKUP = "backup"
    const val TODO_LISTS = "todo_lists"
    const val LAYERS = "layers"
    const val ICS = "ics"
    const val MODELS = "models"
    const val INTEGRATIONS = "integrations"
    const val PERMISSIONS = "permissions"
    const val ADVANCED = "advanced"
    const val DIAGNOSTICS = "diagnostics"
}

/**
 * The dialog itself: what this page is for, an OK, and a box that stops it coming back.
 *
 * Rendered only when the store says it has not been silenced, so a caller places it unconditionally
 * and the decision lives in one place.
 */
@Composable
fun VoxHintDialog(
    store: VoxHintStore,
    hintKey: String,
    title: String,
    body: String,
    okLabel: String,
    dontShowAgainLabel: String
) {
    var dismissedForNow by remember(hintKey) { mutableStateOf(false) }
    var silence by remember(hintKey) { mutableStateOf(false) }
    var known by remember(hintKey) { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(hintKey) { known = store.isDismissed(hintKey) }

    if (known != false || dismissedForNow) return

    AlertDialog(
        onDismissRequest = { dismissedForNow = true },
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clickable { silence = !silence },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(checked = silence, onCheckedChange = { silence = it })
                    Text(dontShowAgainLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Only OK commits the box. Dismissing by tapping away leaves the hint due next
                // time, which is what keeps the tick a decision rather than an accident.
                if (silence) scope.launch { store.dismiss(hintKey) }
                dismissedForNow = true
            }) { Text(okLabel) }
        }
    )
}
