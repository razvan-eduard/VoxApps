package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.ui.labelled
import com.voxapps.design.picklist.Picklist
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.design.settings.SettingsSectionCard

/** Category-resolution defaults for expenses created via Commander's LLM pipeline — both voice
 *  ("Vox, add expense...") and scan cleanup share this same resolution logic (see
 *  ExpensesRepository.addParsedExpense), so these settings govern both, not just voice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsTab(
    settings: ExpensesSettings,
    categories: List<Category>,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Toast on voice save ---
        SettingsSectionCard(languageManager.getString("voice_save_toast")) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    languageManager.getString("voice_save_toast_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.voiceSaveToastEnabled,
                    onCheckedChange = { stateManager.setVoiceSaveToastEnabled(it) }
                )
            }

        }

        // --- Default category for voice/unresolved expenses ---
        SettingsSectionCard(languageManager.getString("default_voice_category")) {
            Text(
                languageManager.getString("default_voice_category_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Picklist(
                items = categories,
                selected = categories.firstOrNull { it.id == settings.defaultVoiceCategoryId },
                itemLabel = { it.labelled() },
                onSelect = { stateManager.setDefaultVoiceCategoryId(it.id) },
                noneLabel = languageManager.getString("none"),
                onNoneSelected = { stateManager.setDefaultVoiceCategoryId(null) }
            )

        }

        // --- Auto-create spoken categories ---
        SettingsSectionCard(languageManager.getString("auto_create_voice_category")) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    languageManager.getString("auto_create_voice_category_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.autoCreateVoiceCategory,
                    onCheckedChange = { stateManager.setAutoCreateVoiceCategory(it) }
                )
            }
        }
    }
}
