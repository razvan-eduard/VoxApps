package com.voxapps.expenses.ui.settings

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
    defaultCurrency: String,
    knownCurrencies: List<String>,
    onAutoCreateFromScansChange: (Boolean) -> Unit,
    onAutoCreateFromNotificationsChange: (Boolean) -> Unit,
    onDefaultCurrencyChange: (String) -> Unit,
    onUpdate: (BankAccount) -> Unit,
    onDelete: (BankAccount) -> Unit,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    var editing by remember { mutableStateOf<BankAccount?>(null) }
    var pendingDelete by remember { mutableStateOf<BankAccount?>(null) }

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

        Text(
            languageManager.getString("default_account_currency"),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp)
        )
        // Offered from what is already in use, plus whatever is typed: a list of every currency in
        // the world is a list nobody scrolls, and the one a person needs is nearly always one their
        // own records already name.
        Picklist(
            items = knownCurrencies,
            selected = defaultCurrency.takeIf { it.isNotBlank() },
            itemLabel = { it },
            onSelect = onDefaultCurrencyChange,
            noneLabel = languageManager.getString("default_currency_follows_app"),
            onNoneSelected = { onDefaultCurrencyChange("") }
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
    }

    editing?.let { account ->
        AccountEditDialog(
            account = account,
            all = accounts,
            knownCurrencies = knownCurrencies,
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
    onConfirm: (BankAccount) -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var label by remember(account.id) { mutableStateOf(account.label.orEmpty()) }
    var currency by remember(account.id) { mutableStateOf(account.currencyCode) }
    var icon by remember(account.id) { mutableStateOf(account.icon) }
    var parentId by remember(account.id) { mutableStateOf(account.parentId) }
    var pickingIcon by remember(account.id) { mutableStateOf(false) }

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
                Text(languageManager.getString("account_currency"), style = MaterialTheme.typography.labelLarge)
                Picklist(
                    items = knownCurrencies,
                    selected = currency.takeIf { it.isNotBlank() },
                    itemLabel = { it },
                    onSelect = { currency = it },
                    noneLabel = languageManager.getString("none"),
                    onNoneSelected = { currency = "" }
                )
                // Only where there is somewhere to put it — a lone account has no parent to choose,
                // and offering an empty picker would be offering a decision nobody can make.
                if (possibleParents.isNotEmpty()) {
                    Text(languageManager.getString("account_belongs_to"), style = MaterialTheme.typography.labelLarge)
                    Picklist(
                        items = possibleParents,
                        selected = possibleParents.firstOrNull { it.id == parentId },
                        itemLabel = { it.displayName() },
                        onSelect = { parentId = it.id },
                        noneLabel = languageManager.getString("account_belongs_to_none"),
                        onNoneSelected = { parentId = null }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    account.copy(
                        label = label.trim().takeIf { it.isNotEmpty() },
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
}
