package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.recordflow.ui.RecordFlowLevelCard
import com.voxapps.recordflow.ui.RecordFlowStrings
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager

/**
 * Everything about reading a receipt, in one place instead of scattered through the general page.
 *
 * These settings answer one question between them — what happens to a photographed document — and
 * they were interleaved with fingerprints, currencies and widget borders, which made the page long
 * without making any of it easier to find. They are banded here by what each band decides: what
 * leaves the device, what is read from the document, and what happens once a scan lands.
 */
@Composable
fun ScanSettingsTab(
    settings: ExpensesSettings,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSectionCard(languageManager.getString("scan_zone_sent")) {
            // --- Attach photo to AI on scan (opt-in; costs real LLM tokens on top of free OCR text,
            // and only takes effect when Vision's own "send photo to AI" setting also provided one). ---
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("attach_photo_on_scan"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        languageManager.getString("attach_photo_on_scan_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.attachPhotoOnScan,
                    onCheckedChange = { stateManager.setAttachPhotoOnScan(it) }
                )
            }

        }
        RecordFlowLevelCard(
            support = ExpensesSettings.SCAN_FLOW_SUPPORT,
            level = ExpensesSettings.scanLevelOf(settings.scanModelUse),
            strings = RecordFlowStrings(
                title = languageManager.getString("scan_model_use"),
                sendNothing = languageManager.getString("flow_send_nothing"),
                sendNothingDesc = languageManager.getString("flow_send_nothing_desc"),
                sendMissing = languageManager.getString("flow_send_missing"),
                sendMissingDesc = languageManager.getString("flow_send_missing_desc"),
                sendHead = languageManager.getString("flow_send_head"),
                sendHeadDesc = languageManager.getString("flow_send_head_desc"),
                sendEverything = languageManager.getString("flow_send_everything"),
                sendEverythingDesc = languageManager.getString("flow_send_everything_desc"),
                fillHead = languageManager.getString("scan_fill_head"),
                fillBody = languageManager.getString("scan_fill_body"),
                cannotSuggest = languageManager.getString("flow_cannot_suggest")
            ),
            onLevelChange = { stateManager.setScanModelUse(it.name) }
        )

        SettingsSectionCard(languageManager.getString("scan_zone_read")) {
            // --- The tax breakdown: never, when the document carries one, or always. Three settings
            // rather than a switch because the honest answer depends on the document — see VatDisplay. ---
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(languageManager.getString("vat_display"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("vat_display_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val vatModes = listOf(
                    ExpensesSettings.VAT_OFF to "vat_display_off",
                    ExpensesSettings.VAT_AUTO to "vat_display_auto",
                    ExpensesSettings.VAT_ON to "vat_display_on"
                )
                for ((mode, key) in vatModes) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { stateManager.setVatDisplay(mode) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.vatDisplay == mode,
                            onClick = { stateManager.setVatDisplay(mode) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(languageManager.getString(key), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                languageManager.getString(key + "_desc"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // --- Attach photo to AI on retry (separate from scan-time — retry re-sends already-staged
            // OCR text after a failed parse, a distinct and less frequent code path). ---
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("attach_photo_on_retry"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        languageManager.getString("attach_photo_on_retry_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.attachPhotoOnRetry,
                    onCheckedChange = { stateManager.setAttachPhotoOnRetry(it) }
                )
            }

        }
        SettingsSectionCard(languageManager.getString("scan_zone_after")) {
            // --- Auto-trigger a line-items rescan the moment an expense gets its FIRST photo attached
            // after being saved (see ExpensesSettings.autoRescanOnFirstAttachment's doc comment for the
            // zero-to-one eligibility rule). ---
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("auto_rescan_on_first_attachment"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        languageManager.getString("auto_rescan_on_first_attachment_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoRescanOnFirstAttachment,
                    onCheckedChange = { stateManager.setAutoRescanOnFirstAttachment(it) }
                )
            }

            // --- Auto-open a scanned receipt's expense once it's actually created (LLM cleanup is
            // async, so this can't happen at scan time itself — see LlmResultReceiver). ---
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(languageManager.getString("auto_open_scanned_expense"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        languageManager.getString("auto_open_scanned_expense_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoOpenScannedExpense,
                    onCheckedChange = { stateManager.setAutoOpenScannedExpense(it) }
                )
            }
        }
    }
}

