package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.expenses.ui.LocalLanguageManager

/**
 * The names this device reads by: the banks, the shops, and the designators that tell a company
 * name from an issuer's.
 *
 * Filed under the data rather than under notifications, because that is what they are. A shop is
 * not a notification setting — it is one of the things this install knows, like its categories and
 * its accounts, and the same list is what a record's fields are matched and offered against.
 */
@Composable
fun NamesSettingsTab(
    settings: ExpensesSettings,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val provided = FieldVocabularies.provided(context)

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Before the lists, because they are what fills them. A capture that read a shop or a bank
        // knows something the next capture would have to work out again; keeping it is the same act
        // as filing the card a message named, and it is switched per route for the same reason.
        SettingsSectionCard(languageManager.getString("learn_names_title")) {
            Text(
                languageManager.getString("learn_names_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow(
                label = languageManager.getString("learn_names_notifications"),
                checked = settings.learnNamesFromNotifications,
                onCheckedChange = { stateManager.setLearnNamesFromNotifications(it) }
            )
            SwitchRow(
                label = languageManager.getString("learn_names_scans"),
                checked = settings.learnNamesFromScans,
                onCheckedChange = { stateManager.setLearnNamesFromScans(it) }
            )
        }

        VocabularySettingsCard(
            provided = provided.banks,
            custom = settings.customBanks,
            disabledKeys = settings.disabledBanks,
            vocabulary = FieldVocabularies.VOCAB_BANK,
            title = languageManager.getString("vocabulary_banks_title"),
            description = languageManager.getString("vocabulary_banks_desc"),
            stateManager = stateManager,
            languageManager = languageManager
        )
        VocabularySettingsCard(
            provided = emptyList(),
            custom = settings.customVendors,
            disabledKeys = settings.disabledVendors,
            vocabulary = FieldVocabularies.VOCAB_VENDOR,
            title = languageManager.getString("vocabulary_vendors_title"),
            description = languageManager.getString("vocabulary_vendors_desc"),
            stateManager = stateManager,
            languageManager = languageManager
        )
        VocabularySettingsCard(
            provided = provided.legalForms,
            custom = settings.customLegalForms,
            disabledKeys = settings.disabledLegalForms,
            vocabulary = FieldVocabularies.VOCAB_LEGAL_FORM,
            title = languageManager.getString("vocabulary_legal_title"),
            description = languageManager.getString("vocabulary_legal_desc"),
            stateManager = stateManager,
            languageManager = languageManager
        )
    }
}
