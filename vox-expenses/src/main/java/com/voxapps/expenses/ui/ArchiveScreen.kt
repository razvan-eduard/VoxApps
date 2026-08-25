package com.voxapps.expenses.ui

import com.voxapps.expenses.domain.accounts.BankAccountTree
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.PaperTapField
import com.voxapps.design.VoxConfirmDialog
import com.voxapps.design.picklist.Picklist
import com.voxapps.design.selection.VoxSelectionBackHandler
import com.voxapps.design.selection.VoxSelectionBar
import com.voxapps.design.selection.rememberVoxSelection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager

/**
 * The records that were put away.
 *
 * Everything here is outside the ledger: no list, total, budget or duplicate check can see it, and
 * nothing in it has been destroyed. That is the whole proposition — a record you no longer want
 * counted, without the decision to lose it, and a way back if you were wrong.
 *
 * Deleting from here is the same act, and asks the same way, as deleting from the list: the same
 * red button that will not accept the first tap. The archive is where somebody goes *to* delete
 * things, so it is the last place that should make it quick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    stateManager: ExpensesStateManager,
    retentionDays: Int,
    onBack: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val records by stateManager.archivedRecords.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by stateManager.bankAccountsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val selection = rememberVoxSelection<Long>()
    var confirmingDelete by remember { mutableStateOf(false) }

    // Leaving the screen is what back means only when there is no selection to leave first.
    BackHandler(enabled = !selection.active) { onBack() }
    VoxSelectionBackHandler(selection)

    Scaffold(
        topBar = {
            if (selection.active) {
                VoxSelectionBar(
                    count = selection.size,
                    title = { languageManager.counted("selection_mode_count", it) },
                    onClose = { selection.clear() },
                    closeContentDescription = languageManager.getString("cancel")
                ) {
                    val listed = records.map { it.expense.id }
                    val allPicked = listed.isNotEmpty() && selection.ids.containsAll(listed)
                    IconButton(onClick = {
                        if (allPicked) selection.clear() else selection.selectAll(listed)
                    }) {
                        Icon(
                            Icons.Filled.Checklist,
                            contentDescription = languageManager.getString(
                                if (allPicked) "selection_select_none" else "selection_select_all"
                            )
                        )
                    }
                    IconButton(onClick = {
                        stateManager.restoreExpenses(selection.ids)
                        selection.clear()
                    }) {
                        Icon(
                            Icons.Filled.Restore,
                            contentDescription = languageManager.getString("selection_restore")
                        )
                    }
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(
                            Icons.Filled.DeleteForever,
                            contentDescription = languageManager.getString("selection_delete_forever"),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(languageManager.getString("archive_title")) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = languageManager.getString("back")
                            )
                        }
                    },
                    actions = {
                        // Everything at once, offered only where there is something to offer it for.
                        if (records.isNotEmpty()) {
                            IconButton(onClick = {
                                selection.selectAll(records.map { it.expense.id })
                                confirmingDelete = true
                            }) {
                                Icon(
                                    Icons.Filled.DeleteForever,
                                    contentDescription = languageManager.getString("archive_delete_all"),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The archive's own rule about itself, at the top of it rather than in settings: it is
            // about these records, and this is where somebody is thinking about them.
            Picklist(
                items = ExpensesSettings.ARCHIVE_RETENTION_CHOICES,
                selected = retentionDays.takeIf { it in ExpensesSettings.ARCHIVE_RETENTION_CHOICES }
                    ?: ExpensesSettings.ARCHIVE_KEEP_FOREVER,
                itemLabel = { retentionLabel(it, languageManager) },
                onSelect = { stateManager.setArchiveRetentionDays(it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                anchor = { value, onClick ->
                    PaperTapField(
                        label = languageManager.getString("archive_retention_label"),
                        value = value,
                        onClick = onClick
                    )
                }
            )

            if (records.isEmpty()) {
                Text(
                    languageManager.getString("archive_empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(records, key = { it.expense.id }) { ewd ->
                        ExpenseCard(
                            expenseWithDetails = ewd,
                            // Nothing opens from here. An archived record is not being kept up to
                            // date, and an editor over one invites exactly that; restore it first
                            // and it is an ordinary record again.
                            onClick = { selection.tap(ewd.expense.id) { selection.start(ewd.expense.id) } },
                            selected = ewd.expense.id in selection,
                            bankName = BankAccountTree.bankNameFor(ewd.expense.bankAccountId, accounts),
                            onLongClick = { selection.start(ewd.expense.id) }
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        VoxConfirmDialog(
            title = languageManager.counted("delete_forever_confirm_title", selection.size),
            message = languageManager.getString("delete_forever_confirm_message"),
            confirmLabel = languageManager.getString("selection_delete_forever"),
            cancelLabel = languageManager.getString("cancel"),
            destructive = true,
            countdownSeconds = DELETE_COUNTDOWN_SECONDS,
            onConfirm = {
                stateManager.deleteExpenses(selection.ids)
                confirmingDelete = false
                selection.clear()
            },
            onDismiss = { confirmingDelete = false }
        )
    }
}

/** Long enough that a reflex has finished, short enough that somebody who means it is not
 *  lectured. */
const val DELETE_COUNTDOWN_SECONDS = 5

fun retentionLabel(days: Int, languageManager: com.voxapps.expenses.domain.localization.LanguageManager): String =
    if (days <= 0) {
        languageManager.getString("archive_retention_forever")
    } else {
        String.format(languageManager.getString("archive_retention_days"), days)
    }
