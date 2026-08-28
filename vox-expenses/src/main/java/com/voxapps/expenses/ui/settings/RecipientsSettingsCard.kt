package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.expenses.data.Recipient
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.textmatch.extract.AccountIdentifiers

/**
 * The recipients this install knows — see [Recipient]. Hosted under the names tab, directly below
 * the learn-names switches, because that is the gate that fills this list: a scanned slip whose
 * beneficiary is new becomes a row here exactly when learning from scans is on.
 */
@Composable
fun RecipientsSettingsCard(stateManager: ExpensesStateManager, modifier: Modifier = Modifier) {
    val languageManager = LocalLanguageManager.current
    val recipients by stateManager.recipientsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<Recipient?>(null) }
    var pendingDelete by remember { mutableStateOf<Recipient?>(null) }
    var adding by remember { mutableStateOf(false) }

    SettingsSectionCard(languageManager.getString("recipients_title"), modifier = modifier) {
        Text(
            languageManager.getString("recipients_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (recipients.isEmpty()) {
            Text(
                languageManager.getString("recipients_none"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            recipients.forEach { recipient ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (recipient.archived) 0.5f else 1f)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { editing = recipient }
                    ) {
                        Text(recipient.name, style = MaterialTheme.typography.bodyLarge)
                        val detail = listOfNotNull(recipient.iban, recipient.bankName).joinToString(" · ")
                        if (detail.isNotEmpty()) {
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { editing = recipient }) {
                        Icon(Icons.Filled.Edit, contentDescription = languageManager.getString("recipient_new"))
                    }
                    IconButton(onClick = { pendingDelete = recipient }) {
                        Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                    }
                }
            }
        }
        TextButton(onClick = { adding = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(languageManager.getString("recipient_new"))
        }
    }

    if (adding) {
        AddRecipientDialog(
            onConfirm = { name, bankName, iban ->
                stateManager.addTypedRecipient(name, bankName, iban)
                adding = false
            },
            onDismiss = { adding = false }
        )
    }

    editing?.let { recipient ->
        RecipientEditDialog(
            recipient = recipient,
            onConfirm = { updated ->
                stateManager.updateRecipient(updated)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    pendingDelete?.let { recipient ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(recipient.name) },
            text = { Text(languageManager.getString("delete_recipient_message")) },
            confirmButton = {
                TextButton(onClick = {
                    stateManager.deleteRecipient(recipient)
                    pendingDelete = null
                }) { Text(languageManager.getString("delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(languageManager.getString("cancel")) }
            }
        )
    }
}

/** A typed IBAN either reads as one or the field objects — the same validator every capture uses,
 *  so a hand-made row matches the same slips a captured one does. Blank is fine: null IBAN. */
@Composable
private fun ibanIsAcceptable(text: String): Boolean =
    text.isBlank() || AccountIdentifiers.single(text.trim())?.kind == AccountIdentifiers.Kind.IBAN

/**
 * Shared by the recipients page and the expense editor's "New recipient…" action, so a row made in
 * either place is the same kind of thing.
 */
@Composable
fun AddRecipientDialog(
    onConfirm: (name: String, bankName: String?, iban: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var iban by remember { mutableStateOf("") }
    val ibanOk = ibanIsAcceptable(iban)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("recipient_new")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(languageManager.getString("recipient_name")) }
                )
                OutlinedTextField(
                    value = bankName, onValueChange = { bankName = it }, singleLine = true,
                    label = { Text(languageManager.getString("recipient_bank")) },
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = iban, onValueChange = { iban = it }, singleLine = true,
                    label = { Text(languageManager.getString("recipient_iban")) },
                    isError = !ibanOk,
                    supportingText = {
                        if (!ibanOk) Text(languageManager.getString("recipient_iban_invalid"))
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && ibanOk,
                onClick = {
                    onConfirm(
                        name.trim(),
                        bankName.trim().takeIf { it.isNotEmpty() },
                        iban.trim().takeIf { it.isNotEmpty() }
                    )
                }
            ) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

@Composable
private fun RecipientEditDialog(
    recipient: Recipient,
    onConfirm: (Recipient) -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf(recipient.name) }
    var bankName by remember { mutableStateOf(recipient.bankName ?: "") }
    var iban by remember { mutableStateOf(recipient.iban ?: "") }
    var archived by remember { mutableStateOf(recipient.archived) }
    val ibanOk = ibanIsAcceptable(iban)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recipient.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(languageManager.getString("recipient_name")) }
                )
                OutlinedTextField(
                    value = bankName, onValueChange = { bankName = it }, singleLine = true,
                    label = { Text(languageManager.getString("recipient_bank")) },
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = iban, onValueChange = { iban = it }, singleLine = true,
                    label = { Text(languageManager.getString("recipient_iban")) },
                    isError = !ibanOk,
                    supportingText = {
                        if (!ibanOk) Text(languageManager.getString("recipient_iban_invalid"))
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
                SwitchRow(
                    label = languageManager.getString("account_archived"),
                    checked = archived,
                    onCheckedChange = { archived = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && ibanOk,
                onClick = {
                    onConfirm(
                        recipient.copy(
                            name = name.trim(),
                            bankName = bankName.trim().takeIf { it.isNotEmpty() },
                            iban = iban.trim().takeIf { it.isNotEmpty() }
                                ?.let { AccountIdentifiers.single(it)?.digits ?: it },
                            archived = archived
                        )
                    )
                }
            ) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}
