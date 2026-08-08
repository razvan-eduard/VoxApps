package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voxapps.expenses.data.ExchangeRateApiKeyStore
import com.voxapps.expenses.data.ExchangeRateRepository
import com.voxapps.expenses.data.ExternalServiceConfig
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.voxapps.services.ServiceProbe
import com.voxapps.design.ConnectionTestAuto
import com.voxapps.design.CommittedTextField

/**
 * Home currency + exchange-rate API key (a real secret, stored separately via
 * [ExchangeRateApiKeyStore] — never in the plain DataStore-backed [ExpensesSettings]) and a manual
 * "Fetch rates now" check so Stage 5's conversion infrastructure can be verified without needing the
 * full Reports screen (Stage 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencySettingsTab(
    settings: ExpensesSettings,
    exchangeRateRepository: ExchangeRateRepository,
    stateManager: ExpensesStateManager,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var homeCurrencyText by remember(settings.homeCurrency) { mutableStateOf(settings.homeCurrency) }
    var apiKeyText by remember { mutableStateOf(ExchangeRateApiKeyStore.get(context) ?: "") }
    var fetching by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    val service = remember { ExternalServiceConfig.exchangeRateService(context) }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(languageManager.getString("currency_settings_title"), style = MaterialTheme.typography.titleMedium)

        Text(languageManager.getString("home_currency_label"), style = MaterialTheme.typography.labelLarge)
        Text(
            languageManager.getString("home_currency_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = homeCurrencyText,
            onValueChange = {
                homeCurrencyText = it.uppercase().take(3)
                stateManager.setHomeCurrency(homeCurrencyText)
            },
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()

        Text(languageManager.getString("exchange_rate_api_key_label"), style = MaterialTheme.typography.labelLarge)
        Text(
            service?.let { String.format(languageManager.getString("exchange_rate_api_key_desc"), it.helpUrl.orEmpty()) }
                ?: languageManager.getString("exchange_rate_service_missing"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Stored when the field is finished with, not per keystroke: a key written a character at a
        // time is written to the secure store a dozen times half-typed, and each write is what the
        // test below keys on.
        CommittedTextField(
            stored = apiKeyText,
            label = languageManager.getString("exchange_rate_api_key_label"),
            identity = service?.id ?: "exchangerate_api",
            masked = true,
            onCommit = { entered ->
                apiKeyText = entered
                ExchangeRateApiKeyStore.set(context, entered.takeIf { key -> key.isNotBlank() })
            }
        )

        // The same card every declared service in VoxApps gets: does it answer, and does it accept
        // this key? Previously the only way to find out was to fetch rates and read the error.
        service?.probeSpec(apiKeyText.takeIf { it.isNotBlank() })?.let { spec ->
            ConnectionTestAuto(
                keys = listOf(spec.id, spec.url, apiKeyText.length),
                testFn = { ServiceProbe.run(spec) },
                testingLabel = languageManager.getString("exchange_rate_testing"),
                onlineLabel = languageManager.getString("exchange_rate_online"),
                offlineLabel = if (spec.missingCredential)
                    languageManager.getString("exchange_rate_needs_key")
                else languageManager.getString("exchange_rate_offline")
            )
        }

        OutlinedButton(
            onClick = {
                fetching = true
                statusText = null
                scope.launch {
                    val result = exchangeRateRepository.getRates(homeCurrencyText, forceRefresh = true)
                    statusText = when (result) {
                        is ExchangeRateRepository.RatesResult.Success -> String.format(
                            languageManager.getString("exchange_rate_fetch_success"),
                            result.rates.size,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(result.fetchedAt))
                        )
                        is ExchangeRateRepository.RatesResult.Error -> String.format(
                            languageManager.getString("exchange_rate_fetch_error"),
                            result.message
                        )
                    }
                    fetching = false
                }
            },
            enabled = !fetching,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (fetching) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text(languageManager.getString("exchange_rate_fetch_button"))
        }

        statusText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
