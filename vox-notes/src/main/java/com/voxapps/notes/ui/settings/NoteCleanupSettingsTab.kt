package com.voxapps.notes.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.rememberRequirementGate
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.notes.data.Note
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.domain.llm.DuplicateGroup
import com.voxapps.notes.state.NotesStateManager
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.notes.ui.LocalLanguageManager

private data class ResolvedGroup(val group: DuplicateGroup, val keep: Note, val duplicates: List<Note>)

/**
 * Note cleanup: the manual "Find duplicate notes" trigger, the scheduled-interval control, and —
 * unlike Auto-Merge Categories — a review section for the LLM's pending suggestion. Real note
 * content isn't cheaply reversible like a category rename, so nothing is deleted until the user
 * explicitly approves specific groups here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCleanupSettingsTab(
    settings: NotesSettings,
    notes: List<Note>,
    stateManager: NotesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val pendingGroups by stateManager.pendingNoteDuplicateGroups.collectAsStateWithLifecycle(initialValue = emptyList())
    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }
    val findDuplicatesGate = rememberRequirementGate(
        satisfied = commanderInstalled,
        requiredMessage = languageManager.getString("commander_required_message")
    ) {
        stateManager.requestNoteDeduplication(context)
        Toast.makeText(context, languageManager.getString("find_duplicate_notes_sent_toast"), Toast.LENGTH_SHORT).show()
    }

    // Resolved against the *current* notes list — a group shrinks or disappears if a note it
    // referenced was edited/deleted since the suggestion arrived, rather than showing stale content.
    val resolvedGroups = remember(pendingGroups, notes) {
        val byId = notes.associateBy { it.id }
        pendingGroups.mapNotNull { group ->
            val keepNote = byId[group.keepId] ?: return@mapNotNull null
            val duplicateNotes = group.duplicateIds.mapNotNull { byId[it] }
            if (duplicateNotes.isEmpty()) null else ResolvedGroup(group, keepNote, duplicateNotes)
        }
    }

    var checkedGroups by remember(resolvedGroups) { mutableStateOf(resolvedGroups.indices.toSet()) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Find duplicate notes (manual trigger) ---
        SettingsSectionCard(languageManager.getString("find_duplicate_notes_button")) {
            Text(
                languageManager.getString("find_duplicate_notes_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (notes.size < 2) {
                Text(
                    languageManager.getString("find_duplicate_notes_need_two"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Button(
                    onClick = findDuplicatesGate.onClick,
                    modifier = Modifier.fillMaxWidth().alpha(findDuplicatesGate.alpha)
                ) {
                    Text(languageManager.getString("find_duplicate_notes_button"))
                }
            }

        }

        // --- Scheduled note cleanup ---
        SettingsSectionCard(languageManager.getString("scheduled_dedup_label")) {
            Text(
                languageManager.getString("scheduled_dedup_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    NotesSettings.INTERVAL_OFF to "scheduled_dedup_off",
                    NotesSettings.INTERVAL_DAILY to "scheduled_dedup_daily",
                    NotesSettings.INTERVAL_WEEKLY to "scheduled_dedup_weekly",
                    NotesSettings.INTERVAL_MONTHLY to "scheduled_dedup_monthly"
                )
                options.forEach { (interval, labelKey) ->
                    FilterChip(
                        selected = settings.scheduledNoteDedupInterval == interval,
                        onClick = { stateManager.setScheduledNoteDedupInterval(context, interval) },
                        label = { Text(languageManager.getString(labelKey)) }
                    )
                }
            }

        }

        // --- Pending suggestion review ---
        if (resolvedGroups.isNotEmpty()) {
            SettingsSectionCard(languageManager.getString("duplicate_notes_pending_title")) {
                resolvedGroups.forEachIndexed { index, resolved ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(
                                    checked = index in checkedGroups,
                                    onCheckedChange = { checked ->
                                        checkedGroups = if (checked) checkedGroups + index else checkedGroups - index
                                    }
                                )
                                Column(modifier = Modifier.padding(top = 12.dp)) {
                                    Text(
                                        languageManager.getString("keep_label"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(notePreview(resolved.keep), style = MaterialTheme.typography.bodyMedium)

                                    resolved.duplicates.forEach { dup ->
                                        Text(
                                            languageManager.getString("duplicate_label"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                        Text(notePreview(dup), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { stateManager.dismissNoteDeduplication() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(languageManager.getString("dismiss_all_button"))
                    }
                    Button(
                        onClick = {
                            val approved = checkedGroups.mapNotNull { resolvedGroups.getOrNull(it)?.group }
                            stateManager.approveNoteDeduplication(approved)
                        },
                        enabled = checkedGroups.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(languageManager.getString("apply_selected_button"))
                    }
                }
            }
        }
    }
}

private fun notePreview(note: Note): String =
    note.title?.takeIf { it.isNotBlank() } ?: note.text.take(60)
