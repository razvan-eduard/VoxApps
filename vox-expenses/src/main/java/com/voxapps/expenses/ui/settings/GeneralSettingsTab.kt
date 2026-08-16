package com.voxapps.expenses.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.design.picklist.Picklist
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.location.ExpensesLocationStore
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.location.LocationSource
import com.voxapps.location.ResolvedLocation
import com.voxapps.location.VoxLocationResolver
import com.voxapps.location.ui.VoxLocationSettingsCard
import com.voxapps.location.ui.VoxLocationUiState
import kotlinx.coroutines.launch
import com.voxapps.design.settings.SchemaUpdatesStrings
import com.voxapps.design.settings.SchemaUpdatesSection

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
    settingsRepo: ExpensesSettingsRepository,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = languageManager.getString("general"), style = MaterialTheme.typography.titleMedium)

        // The same section Commander shows, from :core:design — this app reads a schema of its own
        // (the currency services) and can follow a fork just as well.
        val scope = rememberCoroutineScope()
        SchemaUpdatesSection(
            strings = SchemaUpdatesStrings(
                sectionLabel = languageManager.getString("schema_updates_section"),
                description = languageManager.getString("schema_updates_desc"),
                useRemoteLabel = languageManager.getString("schema_use_remote_label"),
                useRemoteDescription = languageManager.getString("schema_use_remote_desc"),
                repositoryUrlLabel = languageManager.getString("schema_repository_url"),
                checkNow = languageManager.getString("schema_sync_now"),
                followingFormat = languageManager.getString("schema_following"),
                inStep = languageManager.getString("schema_in_step"),
                servingFormat = languageManager.getString("schema_serving"),
                unreachableFormat = languageManager.getString("schema_unreachable"),
                notCheckedYet = languageManager.getString("schema_not_checked"),
                usingBundled = languageManager.getString("schema_using_bundled"),
                problemFormat = languageManager.getString("schema_problem"),
                reasonRejected = languageManager.getString("schema_reason_rejected"),
                reasonUnsigned = languageManager.getString("schema_reason_unsigned"),
                reasonUnreachable = languageManager.getString("schema_reason_unreachable")
            ),
            repositoryUrl = settings.schemaRepoBaseUrl,
            useRemote = settings.useRemoteSchemas,
            onRepositoryUrlChange = { scope.launch { settingsRepo.setSchemaRepoBaseUrl(it) } },
            onUseRemoteChange = { scope.launch { settingsRepo.setUseRemoteSchemas(it) } }
        )

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
        Picklist(
            items = COMMON_CURRENCIES,
            selected = settings.defaultCurrency,
            itemLabel = { it },
            onSelect = { stateManager.setDefaultCurrency(it) }
        )

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

        // --- Widget day-card border (on/off, thickness, color) ---
        Text(languageManager.getString("widget_border_section"), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("widget_border_enabled"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("widget_border_enabled_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.widgetBorderEnabled,
                onCheckedChange = { stateManager.setWidgetBorderEnabled(it) }
            )
        }
        Text(languageManager.getString("widget_border_thickness"), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val thicknessOptions = listOf(
                ExpensesSettings.THICKNESS_THIN to "widget_border_thickness_thin",
                ExpensesSettings.THICKNESS_MEDIUM to "widget_border_thickness_medium",
                ExpensesSettings.THICKNESS_THICK to "widget_border_thickness_thick"
            )
            thicknessOptions.forEach { (thicknessDp, labelKey) ->
                FilterChip(
                    enabled = settings.widgetBorderEnabled,
                    selected = settings.widgetBorderThicknessDp == thicknessDp,
                    onClick = { stateManager.setWidgetBorderThicknessDp(thicknessDp) },
                    label = { Text(languageManager.getString(labelKey)) }
                )
            }
        }
        Text(languageManager.getString("widget_border_color"), style = MaterialTheme.typography.bodyMedium)
        VoxColorSwatchPicker(
            selectedColor = settings.widgetBorderColorArgb,
            onColorSelected = { stateManager.setWidgetBorderColorArgb(it) },
            modifier = Modifier.padding(top = 4.dp),
            customColorDialogTitle = languageManager.getString("custom_color_title"),
            customColorUseLabel = languageManager.getString("use_color_button"),
            customColorCancelLabel = languageManager.getString("cancel"),
            customColorHueLabel = languageManager.getString("hue_label"),
            customColorSaturationLabel = languageManager.getString("saturation_label"),
            customColorBrightnessLabel = languageManager.getString("brightness_label")
        )

        HorizontalDivider()

        // --- Location prefill: one switch governing every place an expense's location field can
        // get auto-filled from GPS (scan, voice, manual entry). Independent of the OS location
        // permission granted in onboarding — that only makes the feature possible, this is whether
        // the user actually wants it. ---
        Text(languageManager.getString("location_section"), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(languageManager.getString("location_prefill_label"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("location_prefill_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.locationPrefillEnabled,
                onCheckedChange = { stateManager.setLocationPrefillEnabled(it) }
            )
        }

        if (settings.locationPrefillEnabled) {
            ExpensesLocationSettingsSection(settingsRepo = settingsRepo)
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
            androidx.compose.material3.AlertDialog(
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

@Composable
private fun ExpensesLocationSettingsSection(settingsRepo: ExpensesSettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val store = remember(settingsRepo) { ExpensesLocationStore(context, settingsRepo) }
    // needsReverseGeocode = true: matches resolveCurrentCityName's existing behavior for the
    // location prefill feature — the same Nominatim resolution is now also shown here.
    val resolver = remember(store) { VoxLocationResolver.create(context, store, needsReverseGeocode = true) }

    var homeTown by remember { mutableStateOf(store.getHomeTownSync()) }
    var cacheTtl by remember { mutableStateOf(store.getCacheTtlSync()) }
    var alwaysUse by remember { mutableStateOf(store.getAlwaysUseHomeTownSync()) }
    var lastLocation by remember {
        mutableStateOf(
            store.getCachedLocationSync()?.let { ResolvedLocation(it.lat, it.lon, LocationSource.CACHE, it.resolvedName) }
        )
    }
    var isRefreshing by remember { mutableStateOf(false) }

    VoxLocationSettingsCard(
        state = VoxLocationUiState(
            lastKnownLocation = lastLocation,
            homeTown = homeTown,
            cacheTtl = cacheTtl,
            alwaysUseHomeTown = alwaysUse,
            isRefreshing = isRefreshing
        ),
        onHomeTownChange = { newHomeTown ->
            homeTown = newHomeTown
            scope.launch { store.setHomeTown(newHomeTown) }
        },
        onCacheTtlChange = { ttl ->
            cacheTtl = ttl
            scope.launch { store.setCacheTtl(ttl) }
        },
        onAlwaysUseHomeTownChange = { enabled ->
            alwaysUse = enabled
            scope.launch { VoxLocationResolver.setAlwaysUseHomeTown(store, enabled) }
        },
        onRefreshClick = {
            isRefreshing = true
            scope.launch {
                lastLocation = resolver.resolveLocation()
                isRefreshing = false
            }
        }
    )
}
