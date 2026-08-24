package com.voxapps.expenses.ui.settings

import com.voxapps.textmatch.extract.AccountIdentifiers
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxapps.design.icon.VoxIconPickerDialog
import com.voxapps.design.picklist.Picklist
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.expenses.data.BankAccount
import com.voxapps.expenses.domain.accounts.BankAccountTree
import com.voxapps.expenses.ui.LocalLanguageManager

/**
 * The cards and accounts money moves through.
 *
 * Deliberately not shaped like the vocabulary cards beside it, because nothing here is learned: an
 * account's format is published, so there is no list supplied with the app, nothing to switch off,
 * and no proposal to accept. What the two switches decide is not whether a reading is trusted — it
 * always is — but whether the app may add to this list without being asked.
 */
@Composable
fun BankAccountsSettingsCard(
    accounts: List<BankAccount>,
    autoCreateFromScans: Boolean,
    autoCreateFromNotifications: Boolean,
    knownCurrencies: List<String>,
    onAutoCreateFromScansChange: (Boolean) -> Unit,
    onAutoCreateFromNotificationsChange: (Boolean) -> Unit,
    onUpdate: (BankAccount) -> Unit,
    onDelete: (BankAccount) -> Unit,
    onAdd: (String) -> Unit,
    /** The banks this device recognises — the vocabulary that names an issuer in a message, so what
     *  an account says it belongs to and what a capture can read are the same list. */
    bankNames: List<String>,
    onAddBank: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    var editing by remember { mutableStateOf<BankAccount?>(null) }
    var pendingDelete by remember { mutableStateOf<BankAccount?>(null) }
    var adding by remember { mutableStateOf(false) }

    SettingsSectionCard(languageManager.getString("bank_accounts_title"), modifier = modifier) {
        Text(
            languageManager.getString("bank_accounts_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SwitchRow(
            label = languageManager.getString("auto_create_accounts_notifications"),
            checked = autoCreateFromNotifications,
            onCheckedChange = onAutoCreateFromNotificationsChange
        )
        SwitchRow(
            label = languageManager.getString("auto_create_accounts_scans"),
            checked = autoCreateFromScans,
            onCheckedChange = onAutoCreateFromScansChange
        )

        if (accounts.isEmpty()) {
            Text(
                languageManager.getString("bank_accounts_none"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            BankAccountTree.display(accounts).forEach { entry ->
                AccountRow(
                    entry = entry,
                    onEdit = { editing = entry.account },
                    onDelete = { pendingDelete = entry.account }
                )
            }
        }

        // Captures are the usual way one appears, but they cannot be the only way: with both
        // switches off, or before any message has arrived, there would otherwise be no way to set
        // up the cards you already know you have.
        TextButton(onClick = { adding = true }, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(
                languageManager.getString("add_account"),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    if (adding) {
        AddAccountDialog(
            onConfirm = { typed -> onAdd(typed); adding = false },
            onDismiss = { adding = false }
        )
    }

    editing?.let { account ->
        AccountEditDialog(
            account = account,
            all = accounts,
            knownCurrencies = knownCurrencies,
            bankNames = bankNames,
            onAddBank = onAddBank,
            onAddAccount = onAdd,
            onConfirm = { onUpdate(it); editing = null },
            onDismiss = { editing = null }
        )
    }

    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(account.displayName()) },
            // Says what survives, because the answer is not obvious and the wrong guess is the one
            // that stops people deleting anything.
            text = { Text(languageManager.getString("delete_account_message")) },
            confirmButton = {
                TextButton(onClick = { onDelete(account); pendingDelete = null }) {
                    Text(languageManager.getString("delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(languageManager.getString("cancel"))
                }
            }
        )
    }
}

/**
 * Typing an account rather than waiting for one to arrive.
 *
 * The same reader the captures use judges what was typed — see
 * [com.voxapps.textmatch.extract.AccountIdentifiers] — so a hand-made account is the same kind of
 * thing as a captured one, and a typo that is not an account is refused here rather than stored as a
 * row nothing will ever match.
 */
@Composable
private fun AddAccountDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    var typed by remember { mutableStateOf("") }
    val reading = remember(typed) { AccountIdentifiers.single(typed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("add_account")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(languageManager.getString("add_account_hint")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Says what it made of the text as the text is typed, so nobody has to guess whether
                // a card number was understood before committing to it.
                Text(
                    reading?.let { BankAccount.defaultLabel(it.kind.name, it.digits) }
                        ?: languageManager.getString("add_account_unrecognised"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reading != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(typed) }, enabled = reading != null) {
                Text(languageManager.getString("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AccountRow(entry: BankAccountTree.Entry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    val account = entry.account
    Row(
        // Indented where it sits under an account, which is the whole of what says so.
        modifier = Modifier.fillMaxWidth().padding(start = (entry.depth * 20).dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onEdit),
            contentAlignment = Alignment.Center
        ) {
            Text(account.icon ?: "＋", fontSize = if (account.icon != null) 17.sp else 13.sp)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(account.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val beneath = listOfNotNull(
                account.currencyCode.takeIf { it.isNotBlank() },
                account.bankName?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (beneath.isNotEmpty()) {
                Text(
                    beneath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = languageManager.getString("edit"))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
        }
    }
}

/** Naming an account, giving it a currency and an icon, and saying which account a card draws on. */
@Composable
private fun AccountEditDialog(
    account: BankAccount,
    all: List<BankAccount>,
    knownCurrencies: List<String>,
    bankNames: List<String>,
    onAddBank: (String) -> Unit,
    onAddAccount: (String) -> Unit,
    onConfirm: (BankAccount) -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var label by remember(account.id) { mutableStateOf(account.label.orEmpty()) }
    var bankName by remember(account.id) { mutableStateOf(account.bankName.orEmpty()) }
    var currency by remember(account.id) { mutableStateOf(account.currencyCode) }
    var icon by remember(account.id) { mutableStateOf(account.icon) }
    var parentId by remember(account.id) { mutableStateOf(account.parentId) }
    var pickingIcon by remember(account.id) { mutableStateOf(false) }
    var namingBank by remember(account.id) { mutableStateOf(false) }
    var addingParent by remember(account.id) { mutableStateOf(false) }

    val possibleParents = remember(account.id, all) {
        all.filter { BankAccountTree.canParent(account, it, all) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(account.displayName()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { pickingIcon = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(icon ?: "＋", fontSize = if (icon != null) 22.sp else 15.sp)
                    }
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(languageManager.getString("account_name")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                }
                // Chosen from the vocabulary that recognises an issuer in a message, not typed:
                // one list means an account's bank and a capture's bank are the same word, so a name
                // written here is one a later notification can also be read by. Adding one adds it
                // there — two things worth having are one action.
                Text(languageManager.getString("account_bank_name"), style = MaterialTheme.typography.labelLarge)
                Picklist(
                    // A name a capture wrote and nobody listed is still what this row says, so it
                    // is offered alongside the list rather than silently reading as unset.
                    items = if (bankName.isNotBlank() && bankNames.none { it.equals(bankName, ignoreCase = true) }) {
                        listOf(bankName) + bankNames
                    } else bankNames,
                    selected = bankName.takeIf { it.isNotBlank() },
                    itemLabel = { it },
                    onSelect = { bankName = it },
                    noneLabel = languageManager.getString("none"),
                    onNoneSelected = { bankName = "" },
                    searchPlaceholder = languageManager.getString("filter_search_hint"),
                    actionLabel = languageManager.getString("account_bank_new"),
                    onAction = { namingBank = true }
                )
                Text(languageManager.getString("account_currency"), style = MaterialTheme.typography.labelLarge)
                Picklist(
                    items = knownCurrencies,
                    selected = currency.takeIf { it.isNotBlank() },
                    itemLabel = { it },
                    onSelect = { currency = it },
                    noneLabel = languageManager.getString("none"),
                    onNoneSelected = { currency = "" }
                )
                // Shown wherever this row could belong to something, even with no candidate yet:
                // an empty list still offers making one, and the case with nothing to choose from is
                // exactly the case where that is the answer. A row already holding cards is the one
                // that cannot — the one-level rule, see BankAccountTree.canParent.
                if (BankAccountTree.childrenOf(account.id, all).isEmpty()) {
                    Text(languageManager.getString("account_belongs_to"), style = MaterialTheme.typography.labelLarge)
                    Picklist(
                        items = possibleParents,
                        selected = possibleParents.firstOrNull { it.id == parentId },
                        itemLabel = { it.displayName() },
                        onSelect = { parentId = it.id },
                        noneLabel = languageManager.getString("account_belongs_to_none"),
                        onNoneSelected = { parentId = null },
                        // The account a card belongs to may not exist yet, and sending somebody back
                        // to the list to make one, then in again to point at it, is three steps for
                        // one intention.
                        searchPlaceholder = languageManager.getString("filter_search_hint"),
                        actionLabel = languageManager.getString("account_belongs_to_new"),
                        onAction = { addingParent = true }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    account.copy(
                        label = label.trim().takeIf { it.isNotEmpty() },
                        bankName = bankName.trim().takeIf { it.isNotEmpty() },
                        currencyCode = currency,
                        icon = icon,
                        parentId = parentId
                    )
                )
            }) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )

    if (pickingIcon) {
        VoxIconPickerDialog(
            title = languageManager.getString("category_icon_title"),
            selected = icon,
            onPick = { picked -> icon = picked; pickingIcon = false },
            onDismiss = { pickingIcon = false },
            noneLabel = languageManager.getString("category_icon_none"),
            customLabel = languageManager.getString("category_icon_custom"),
            confirmLabel = languageManager.getString("save"),
            cancelLabel = languageManager.getString("cancel")
        )
    }

    if (namingBank) {
        // Added to the vocabulary, not only to this row: a bank named here is one a later
        // notification can be read by.
        TypedNameDialog(
            title = languageManager.getString("account_bank_new"),
            label = languageManager.getString("account_bank_name"),
            onConfirm = { typed ->
                bankName = typed
                onAddBank(typed)
                namingBank = false
            },
            onDismiss = { namingBank = false }
        )
    }

    if (addingParent) {
        AddAccountDialog(
            onConfirm = { typed ->
                // The row is created here and pointed at from the list once it exists; the card's
                // own parent is set on the next pass rather than guessed at now.
                onAddAccount(typed)
                addingParent = false
            },
            onDismiss = { addingParent = false }
        )
    }
}

/** One line of text, asked for and returned — a bank's name, and nothing else. */
@Composable
private fun TypedNameDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}
