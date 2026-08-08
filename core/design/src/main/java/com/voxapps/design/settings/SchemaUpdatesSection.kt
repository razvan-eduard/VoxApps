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
    val autoUpdateLabel: String,
    val repositoryUrlLabel: String,
    val checkNow: String,
    /** Three counts, in order: updated, unchanged, failed. */
    val reportFormat: String,
    val sourceBundled: String,
    val sourceAccepted: String,
    /** Two counts: from the repository, shipped with the app. */
    val sourceMixedFormat: String
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
    autoUpdate: Boolean,
    onRepositoryUrlChange: (String) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onSchemasChanged: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var refreshedAt by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.sectionLabel, style = MaterialTheme.typography.titleSmall)
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
            Text(
                text = strings.autoUpdateLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = autoUpdate, onCheckedChange = onAutoUpdateChange)
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
                    report = null
                    scope.launch {
                        // Every schema this app loaded — the catalog is the list, so a section that
                        // knows nothing about which app it is in still covers all of them.
                        val results = SchemaCatalog.refreshAll(repositoryUrl)
                        val updated = results.count { it.value is RemoteSchema.Refreshed.Updated }
                        val unchanged = results.count { it.value is RemoteSchema.Refreshed.Unchanged }
                        report = String.format(
                            strings.reportFormat,
                            updated, unchanged, results.size - updated - unchanged
                        )
                        if (updated > 0) onSchemasChanged()
                        refreshedAt++
                        syncing = false
                    }
                }
            ) {
                if (syncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = strings.checkNow)
            }
            Text(
                text = report ?: strings.checkNow,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The files, then one line for where they came from. Recomputed after a refresh, which is
        // what refreshedAt is for — provenance is read from the catalog rather than held in state.
        val provenance = remember(refreshedAt) { SchemaCatalog.provenance() }
        if (provenance.isNotEmpty()) {
            Text(
                text = provenance.joinToString(" · ") { it.fileName },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val fromRepo = provenance.count { it.source == RemoteSchema.Source.ACCEPTED }
            Text(
                text = when (fromRepo) {
                    0 -> strings.sourceBundled
                    provenance.size -> strings.sourceAccepted
                    else -> String.format(strings.sourceMixedFormat, fromRepo, provenance.size - fromRepo)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
