package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

        HorizontalDivider()

        // --- VAT breakdown display (per-line-item net/VAT/gross, when a receipt provides it) ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("vat_display"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("vat_display_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.vatDisplayEnabled,
                onCheckedChange = { stateManager.setVatDisplayEnabled(it) }
            )
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
