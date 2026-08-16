package com.voxapps.design.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.CommittedTextField
import com.voxapps.services.RemoteSchema
import com.voxapps.services.SchemaCatalog
import kotlinx.coroutines.launch

/** Everything this section says, supplied by the app so it stays translated in its own strings. */
data class SchemaUpdatesStrings(
    val sectionLabel: String,
    val description: String,
    val useRemoteLabel: String,
    val useRemoteDescription: String,
    val repositoryUrlLabel: String,
    val checkNow: String,
    /** Which repository this app follows. One argument: the host and path. */
    val followingFormat: String,
    /** Everything the repository serves is what the app is already running. */
    val inStep: String,
    /** One argument: how many schemas the repository is currently supplying. */
    val servingFormat: String,
    /** The repository could not be read at all — one argument: why. */
    val unreachableFormat: String,
    /** No check has succeeded yet this session. */
    val notCheckedYet: String,
    /** Shown in place of everything else when the switch is off. */
    val usingBundled: String,
    /** "%1$s — %2$s": which file, and what went wrong with it. */
    val problemFormat: String,
    /** It was read but refused — unparseable, or emptied enough to look like a broken download. */
    val reasonRejected: String,
    /** It parsed, but no valid signature from this repository covers it. */
    val reasonUnsigned: String,
    /** That one file could not be read — one argument: why (404, timeout). */
    val reasonUnreachable: String
)

/**
 * Which repository serves this app's schemas, whether it is asked, and what is in force.
 *
 * Written once for every app that reads schemas, because the arrangement is the same everywhere and
 * the differences are only in storage: Commander keeps these in its DataStore, another app in its
 * own. The app supplies the current values and two setters; nothing here persists anything.
 *
 * The section deliberately shows what is in force. Otherwise "am I running what I shipped, or
 * something I fetched?" is answerable only by behaviour, and a schema that quietly took effect is
 * exactly the thing this screen exists to make deliberate.
 */
@Composable
fun SchemaUpdatesSection(
    strings: SchemaUpdatesStrings,
    repositoryUrl: String,
    useRemote: Boolean,
    onRepositoryUrlChange: (String) -> Unit,
    onUseRemoteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onSchemasChanged: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var lastResults by remember { mutableStateOf<Map<String, RemoteSchema.Refreshed>?>(null) }
    var refreshedAt by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = strings.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.useRemoteLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = strings.useRemoteDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            /*
             * The one deliberate decision here: follow the repository, or run what shipped.
             *
             * Turning it off *is* the reset — the downloaded copies are deleted and the build's own
             * schemas apply again — which is why there is no separate "reset schemas" button
             * anywhere. Turning it on asks the repository straight away rather than waiting for a
             * launch, since someone who just flipped it is asking for exactly that.
             */
            Switch(
                checked = useRemote,
                onCheckedChange = { enabled ->
                    onUseRemoteChange(enabled)
                    if (!enabled) {
                        SchemaCatalog.resetAll()
                        lastResults = null
                        refreshedAt++
                        onSchemasChanged()
                    } else {
                        scope.launch {
                            val results = SchemaCatalog.refreshAll(repositoryUrl)
                            lastResults = results
                            refreshedAt++
                            onSchemasChanged()
                        }
                    }
                }
            )
        }

        if (!useRemote) {
            Text(
                text = strings.usingBundled,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        // Committed when the field is finished with: a URL stored per keystroke is a URL fetched
        // per keystroke by whatever asks next.
        CommittedTextField(
            stored = repositoryUrl,
            label = strings.repositoryUrlLabel,
            identity = "schema-repository",
            onCommit = onRepositoryUrlChange
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = !syncing,
                onClick = {
                    syncing = true
                    scope.launch {
                        // Every schema this app loaded — the catalog is the list, so a section that
                        // knows nothing about which app it is in still covers all of them.
                        val results = SchemaCatalog.refreshAll(repositoryUrl)
                        if (results.values.any { it is RemoteSchema.Refreshed.Updated }) onSchemasChanged()
                        lastResults = results
                        refreshedAt++
                        syncing = false
                    }
                }
            ) {
                if (syncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = strings.checkNow)
            }

            /*
             * Two facts, kept apart on purpose.
             *
             * *Which* repository this app follows is a choice about the whole set. *Whether it
             * answered* is what a check tells you. Neither is a per-file property, which is what the
             * earlier "this file came from assets, that one from the repository" line implied — and
             * with the repository as the source of truth, every file comes from it anyway.
             */
            val results = lastResults
            val serving = remember(refreshedAt) {
                SchemaCatalog.provenance().count { it.source == RemoteSchema.Source.ACCEPTED }
            }
            val unreachable = results?.values
                ?.filterIsInstance<RemoteSchema.Refreshed.Unreachable>()
                ?.firstOrNull()

            Column {
                Text(
                    text = String.format(strings.followingFormat, repositoryUrl.substringAfter("://")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when {
                        unreachable != null -> String.format(strings.unreachableFormat, unreachable.reason)
                        results == null && serving == 0 -> strings.notCheckedYet
                        serving == 0 -> strings.usingBundled
                        results != null -> strings.inStep
                        else -> String.format(strings.servingFormat, serving)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                /*
                 * The files that did not make it, named.
                 *
                 * Every way a repository can be wrong — a private fork, a branch that is not main, a
                 * renamed folder, a list emptied to disable something, an unsigned change — reaches
                 * the phone as the same nothing: the app keeps what it had and says "in step". That
                 * is right as behaviour and useless as feedback, and it is the whole of what a
                 * non-developer has to debug against.
                 *
                 * Successes stay silent; only problems earn a line.
                 */
                results?.forEach { (fileName, outcome) ->
                    val reason = when (outcome) {
                        is RemoteSchema.Refreshed.Rejected -> strings.reasonRejected
                        is RemoteSchema.Refreshed.Unsigned -> strings.reasonUnsigned
                        is RemoteSchema.Refreshed.Unreachable -> String.format(
                            strings.reasonUnreachable, outcome.reason.take(60)
                        )
                        else -> null
                    }
                    if (reason != null) {
                        Text(
                            text = String.format(strings.problemFormat, fileName, reason),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
