package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager

/** Fixed, common-currency list for the "Default currency" picker — not the full ISO 4217 set. */
private val COMMON_CURRENCIES = listOf(
    "RON", "EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD", "SEK", "NOK",
    "DKK", "PLN", "CZK", "HUF", "TRY", "CNY", "INR", "BRL"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsTab(
    settings: ExpensesSettings,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = languageManager.getString("general"), style = MaterialTheme.typography.titleMedium)

        // --- Theme (mirrors vox-commander's General settings) ---
        Text(languageManager.getString("theme_section"), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val themeModes = listOf(
                ExpensesSettings.THEME_SYSTEM to "theme_system",
                ExpensesSettings.THEME_LIGHT to "theme_light",
                ExpensesSettings.THEME_DARK to "theme_dark"
            )
            themeModes.forEach { (mode, labelKey) ->
                FilterChip(
                    selected = settings.themeDarkMode == mode,
                    onClick = { stateManager.setThemeDarkMode(mode) },
                    label = { Text(languageManager.getString(labelKey)) }
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("theme_colored"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    languageManager.getString("theme_colored_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = settings.themeColored, onCheckedChange = { stateManager.setThemeColored(it) })
        }

        HorizontalDivider()

        // --- Require fingerprint ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("require_fingerprint"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("require_fingerprint_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.isBiometricRequired,
                onCheckedChange = { stateManager.setBiometricRequired(it) }
            )
        }

        HorizontalDivider()

        // --- Session timeout ---
        Text(languageManager.getString("session_timeout"), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                ExpensesSettings.TIMEOUT_30M to "timeout_30m",
                ExpensesSettings.TIMEOUT_1H to "timeout_1h",
                ExpensesSettings.TIMEOUT_1D to "timeout_1d",
                ExpensesSettings.TIMEOUT_UNLIMITED to "timeout_unlimited"
            )
            options.forEach { (minutes, labelKey) ->
                FilterChip(
                    selected = settings.sessionTimeoutMinutes == minutes,
                    onClick = { stateManager.setSessionTimeoutMinutes(minutes) },
                    label = { Text(languageManager.getString(labelKey)) }
                )
            }
        }

        HorizontalDivider()

        // --- Default currency ---
        Text(languageManager.getString("default_currency_label"), style = MaterialTheme.typography.labelLarge)
        Text(
            languageManager.getString("default_currency_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var currencyMenuExpanded by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { currencyMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(settings.defaultCurrency)
            }
            DropdownMenu(expanded = currencyMenuExpanded, onDismissRequest = { currencyMenuExpanded = false }) {
                COMMON_CURRENCIES.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        onClick = {
                            stateManager.setDefaultCurrency(code)
                            currencyMenuExpanded = false
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        // --- Decimal separator (which character amount/quantity/price fields use and expect on the
        // edit screen — independent of the device's locale, see ExpensesSettings.decimalSeparator) ---
        Text(languageManager.getString("decimal_separator_label"), style = MaterialTheme.typography.labelLarge)
        Text(
            languageManager.getString("decimal_separator_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                ExpensesSettings.DECIMAL_PERIOD to "decimal_separator_period",
                ExpensesSettings.DECIMAL_COMMA to "decimal_separator_comma"
            )
            options.forEach { (value, labelKey) ->
                FilterChip(
                    selected = settings.decimalSeparator == value,
                    onClick = { stateManager.setDecimalSeparator(value) },
                    label = { Text(languageManager.getString(labelKey)) }
                )
            }
        }

        HorizontalDivider()

        // --- Calendar view (opt-in; changes the primary browsing paradigm) ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("calendar_view"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("calendar_view_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.calendarViewEnabled,
                onCheckedChange = { stateManager.setCalendarViewEnabled(it) }
            )
        }

        HorizontalDivider()

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

        HorizontalDivider()

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

        HorizontalDivider()

        // --- Debug logging (off by default; only turn on while actively debugging) ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("debug_logging"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("debug_logging_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.debugLoggingEnabled,
                onCheckedChange = { stateManager.setDebugLoggingEnabled(it) }
            )
        }

        // --- Debug Toasts ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("debug_toasts"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("debug_toasts_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.debugToastsEnabled,
                onCheckedChange = { stateManager.setDebugToastsEnabled(it) }
            )
        }

        HorizontalDivider()

        // --- Danger Zone: Delete All ---
        var showDeleteAllConfirm by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("delete_all_expenses"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                Text(
                    languageManager.getString("delete_all_expenses_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Button(
                onClick = { showDeleteAllConfirm = true },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(languageManager.getString("delete"))
            }
        }

        if (showDeleteAllConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteAllConfirm = false },
                title = { Text(languageManager.getString("delete_all_confirm_title")) },
                text = { Text(languageManager.getString("delete_all_confirm_message")) },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            stateManager.deleteAllExpenses()
                            showDeleteAllConfirm = false
                        }
                    ) {
                        Text(languageManager.getString("delete"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showDeleteAllConfirm = false }) {
                        Text(languageManager.getString("cancel"))
                    }
                }
            )
        }

        if (com.voxapps.expenses.BuildConfig.DEBUG) {
            HorizontalDivider()
            Text(languageManager.getString("debug_section"), style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.OutlinedButton(
                onClick = { stateManager.seedDebugTestData() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(languageManager.getString("add_test_data"))
            }
        }
    }
}
