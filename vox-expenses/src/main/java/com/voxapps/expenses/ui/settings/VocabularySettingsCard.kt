package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.textmatch.extract.VocabularyClassifier
import kotlinx.coroutines.launch

/**
 * The words that decide which half of a payment notification is the merchant.
 *
 * Two lists, shown apart because they do different work: a company designator names the vendor
 * outright, an issuer names the bank and leaves the *other* field as the vendor. Neither is a list
 * of merchants, and the wording says so — a shop name added here would do nothing.
 *
 * Supplied words are shown alongside your own but are never mixed with them. A supplied word cannot
 * be deleted, only switched off: the list underneath is replaced wholesale by the next update, so
 * deleting would be undone without warning, while switching off is remembered against the word and
 * survives. Your own words are yours to remove outright.
 */
@Composable
fun VocabularySettingsCard(
    provided: List<String>,
    custom: Set<String>,
    disabledKeys: Set<String>,
    vocabulary: String,
    title: String,
    description: String,
    stateManager: ExpensesStateManager,
    languageManager: LanguageManager
) {
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SettingsSectionCard(title) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { adding = !adding; error = null }) {
                Icon(
                    if (adding) Icons.Filled.Close else Icons.Filled.Add,
                    contentDescription = languageManager.getString(
                        if (adding) "cancel" else "vocabulary_add_term"
                    )
                )
            }
        }

        if (adding) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error != null,
                label = { Text(languageManager.getString("vocabulary_add_term")) },
                supportingText = error?.let { { Text(it) } }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    scope.launch {
                        when (val rejection = stateManager.addVocabularyTerm(vocabulary, draft)) {
                            null -> { draft = ""; adding = false; error = null }
                            else -> error = languageManager.getString(
                                when (rejection) {
                                    FieldVocabularies.Rejection.EMPTY -> "vocabulary_rejected_empty"
                                    FieldVocabularies.Rejection.ALREADY_PRESENT -> "vocabulary_rejected_present"
                                    FieldVocabularies.Rejection.IN_THE_OTHER_LIST -> "vocabulary_rejected_other_list"
                                }
                            )
                        }
                    }
                }) { Text(languageManager.getString("vocabulary_add_confirm")) }
            }
        }

        if (custom.isNotEmpty()) {
            SectionHeader(
                label = languageManager.getString("vocabulary_yours"),
                terms = custom,
                disabledKeys = disabledKeys,
                vocabulary = vocabulary,
                stateManager = stateManager,
                languageManager = languageManager
            )
            custom.sorted().forEach { term ->
                val off = VocabularyClassifier.termKey(term) in disabledKeys
                TermRow(
                    term = term,
                    disabled = off,
                    trailing = {
                        // Switching off and deleting are different acts, so a word of one's own
                        // carries both: kept-but-unused is a state worth being able to reach.
                        IconButton(onClick = {
                            stateManager.setVocabularyTermEnabled(vocabulary, term, enabled = off)
                        }) {
                            Icon(
                                if (off) Icons.Filled.Undo else Icons.Filled.Remove,
                                contentDescription = languageManager.getString(
                                    if (off) "vocabulary_enable_term" else "vocabulary_disable_term"
                                )
                            )
                        }
                        IconButton(onClick = { stateManager.removeVocabularyTerm(vocabulary, term) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = languageManager.getString("delete")
                            )
                        }
                    }
                )
            }
        }

        // The divider is the delimitation: below it is the supplied list, which behaves differently
        // from anything above it and is not editable in the same way.
        if (provided.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            SectionHeader(
                label = languageManager.getString("vocabulary_provided").format(provided.size),
                terms = provided,
                disabledKeys = disabledKeys,
                vocabulary = vocabulary,
                stateManager = stateManager,
                languageManager = languageManager
            )
            provided.forEach { term ->
                val off = VocabularyClassifier.termKey(term) in disabledKeys
                TermRow(
                    term = term,
                    disabled = off,
                    trailing = {
                        IconButton(onClick = {
                            stateManager.setVocabularyTermEnabled(vocabulary, term, enabled = off)
                        }) {
                            Icon(
                                if (off) Icons.Filled.Undo else Icons.Filled.Remove,
                                contentDescription = languageManager.getString(
                                    if (off) "vocabulary_enable_term" else "vocabulary_disable_term"
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

/** A word and what may be done to it — struck through and dimmed when it has been switched off,
 *  so a list that still shows every supplied word also shows which of them are in force. */
@Composable
private fun TermRow(term: String, disabled: Boolean, trailing: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            term,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (disabled) TextDecoration.LineThrough else null,
            color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/** A section's name and the one action that applies to all of it. Shown only where there is
 *  something to act on, so an empty section is a label and nothing more. */
@Composable
private fun SectionHeader(
    label: String,
    terms: Collection<String>,
    disabledKeys: Set<String>,
    vocabulary: String,
    stateManager: ExpensesStateManager,
    languageManager: LanguageManager
) {
    val allOff = terms.isNotEmpty() && terms.all { VocabularyClassifier.termKey(it) in disabledKeys }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (terms.isNotEmpty()) {
            TextButton(onClick = {
                stateManager.setVocabularySectionEnabled(vocabulary, terms, enabled = allOff)
            }) {
                Text(
                    languageManager.getString(
                        if (allOff) "vocabulary_enable_all" else "vocabulary_disable_all"
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
