package com.voxapps.expenses.ui.settings

import com.voxapps.expenses.ExpensesApplication
import com.voxapps.onboarding.VoxHintKeys
import com.voxapps.onboarding.VoxHintDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.apppicker.AppPickerCard
import com.voxapps.apppicker.AppPickerStrings
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.apps.LauncherAppsCache
import com.voxapps.expenses.domain.llm.PendingNotificationExpense
import com.voxapps.expenses.receiver.PaymentNotificationListenerService
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.expenses.ui.formatAmount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.design.settings.VoxSuggestionChip
import com.voxapps.expenses.data.FieldVocabularies
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedTextField

/** Cooldown after tapping "Force-check notifications now" — forceRecheckNow() is fire-and-forget
 *  with no completion signal, so this is a simple guard against a double-tap dispatching (and, with
 *  auto-accept on, inserting) the same currently-visible notification twice. */
private const val FORCE_CHECK_COOLDOWN_MILLIS = 5000L

/**
 * Best-effort deep link to Honor/Huawei's own "App launch management" screen — confirmed on-device
 * (via logcat: "Service starting has been prevented by iaware or trustsbase") that this OEM's own
 * background-process manager can block [PaymentNotificationListenerService] from ever rebinding
 * after the process is killed, independently of (and not fixed by) the stock-Android battery
 * optimization exemption already offered above. There's no public Intent action for this screen, so
 * this targets the known component directly and falls back to this app's own App Info page — where
 * some OEM skins surface the same "Auto-launch"/"Run in background" controls — if that component
 * isn't present at all (e.g. a non-Honor/Huawei device).
 */
private fun openAppLaunchManagement(context: Context) {
    val honorStartupManager = Intent().apply {
        component = ComponentName(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
    }
    try {
        context.startActivity(honorStartupManager)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(context, "Couldn't open system settings", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The riskiest permission this app asks for — a [android.service.notification.NotificationListenerService]
 * can see every notification on the device — so this screen is explicit about what's opt-in: the OS
 * grant (deep-linked to system Settings, can't be requested via a normal runtime dialog) only lets the
 * *service exist*; the actual per-app allowlist below is what determines whether any notification
 * content is ever inspected, and defaults to nothing selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCaptureSettingsTab(
    paymentSourcePackages: Set<String>,
    bankingSourcePackages: Set<String>,
    autoAcceptNotificationExpenses: Boolean,
    notificationModelUse: String,
    notificationAssumedDirection: String,
    captureAmountlessPayments: Boolean,
    dismissNotificationOnCapture: Boolean,
    settings: ExpensesSettings,
    stateManager: ExpensesStateManager,
    settingsRepo: ExpensesSettingsRepository,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    VoxHintDialog(
        store = (LocalContext.current.applicationContext as ExpensesApplication).container.hintStore,
        hintKey = VoxHintKeys.NOTIFICATION_CAPTURE,
        title = languageManager.getString("hint_notification_capture_title"),
        body = languageManager.getString("hint_notification_capture_body"),
        okLabel = languageManager.getString("hint_ok"),
        dontShowAgainLabel = languageManager.getString("hint_dont_show_again")
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var accessGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }

    val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    }

    // Both OS grants happen in system Settings / a system dialog, not a normal runtime permission
    // dialog, so there's no ActivityResult callback to catch either change — re-checking on
    // ON_RESUME (fires when the user backs out of system Settings back into this screen) keeps both
    // buttons' status accurate without a manual "re-check" action.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                batteryOptimizationIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // forceRecheckNow() dispatches one broadcast per currently-visible notification and returns
    // immediately (fire-and-forget, no completion callback) — the button gives no feedback beyond a
    // toast, which invites an impatient double-tap that would dispatch (and, with auto-accept on,
    // insert) the same notification twice. A short cooldown after each tap is the simplest guard
    // against that, since there's no real "in flight" signal to key off instead.
    var forceCheckOnCooldown by remember { mutableStateOf(false) }

    // LauncherAppsCache is warmed synchronously in ExpensesContainer's init, before any UI composes —
    // this just reads the already-populated cache rather than re-scanning on every screen open.
    var installedApps by remember { mutableStateOf(LauncherAppsCache.cachedApps) }

    val appPickerStrings = remember(languageManager) {
        AppPickerStrings(
            searchPlaceholder = languageManager.getString("search_apps_placeholder"),
            clear = languageManager.getString("clear"),
            showAllApps = languageManager.getString("show_all_apps"),
            showUserApps = languageManager.getString("show_user_apps"),
            showSystemApps = languageManager.getString("show_system_apps"),
            noAppsFound = languageManager.getString("no_apps_found"),
            expand = languageManager.getString("expand"),
            collapse = languageManager.getString("collapse"),
            noneLabel = languageManager.getString("none_system_default"),
            notSelected = languageManager.getString("not_selected"),
            noAppsSelected = languageManager.getString("no_apps_selected"),
            defaultAppSummaryFormat = languageManager.getString("default_app_summary"),
            appsSelectedNoDefaultFormat = languageManager.getString("apps_selected_no_default"),
            starredCountSummaryFormat = languageManager.getString("payment_apps_banks_summary"),
            selected = languageManager.getString("selected"),
            // This screen's star toggle means "this is a bank app", not "the default app" — the
            // shared AppPickerCard star icon is generic, only the wording passed in here differs.
            setAsDefault = languageManager.getString("mark_as_bank"),
            removeDefault = languageManager.getString("unmark_as_bank"),
            done = languageManager.getString("done"),
            cancel = languageManager.getString("cancel")
        )
    }

    val pendingEntries by stateManager.pendingNotificationExpenses.collectAsStateWithLifecycle(initialValue = emptyList())

    // This tab's caller (SettingsScreen) passes a plain Modifier.fillMaxSize() with no scroll of its
    // own, and this was the only settings tab whose content could ever exceed one screen's height —
    // confirmed on-device: the payment-source-apps picker's expanded search box + app list were being
    // laid out below the visible viewport with no way to reach them, not actually empty.
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSectionCard(languageManager.getString("notification_capture_title")) {
            Text(
                languageManager.getString("notification_capture_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (accessGranted) Color(0xFF4CAF50) else Color(0xFFF44336)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(languageManager.getString("grant_notification_access_button"), color = Color.White)
            }

            Text(
                languageManager.getString("battery_optimization_warning"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    if (batteryOptimizationIgnored) {
                        Toast.makeText(context, languageManager.getString("battery_optimization_already_disabled"), Toast.LENGTH_SHORT).show()
                    } else {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (batteryOptimizationIgnored) Color(0xFF4CAF50) else Color(0xFFF44336)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(languageManager.getString("disable_battery_optimization_button"), color = Color.White)
            }

            // Distinct from (and, on an affected OEM, more load-bearing than) the battery-optimization
            // exemption above: Honor/Huawei's own "App launch management" gate can block this service
            // from ever rebinding after the process is killed — confirmed on-device via logcat
            // ("Service starting has been prevented by iaware or trustsbase") — independently of whatever
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS reports, since that's a separate, stock-Android
            // mechanism this OEM's own manager sits on top of. No public Intent action reaches this
            // screen directly, so this best-effort deep-links straight to that OEM's known component and
            // falls back to this app's own App Info page (where "Launch" / "Auto-launch" controls live on
            // some OEM skins) if that component isn't present at all.
            Text(
                languageManager.getString("app_launch_management_warning"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { openAppLaunchManagement(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(languageManager.getString("open_app_launch_management_button"))
            }

            OutlinedButton(
                onClick = {
                    if (!accessGranted) {
                        Toast.makeText(context, languageManager.getString("grant_notification_access_button"), Toast.LENGTH_SHORT).show()
                    } else if (!forceCheckOnCooldown) {
                        // Re-checks every notification currently in the shade directly against the
                        // "already processed" guard, bypassing it entirely for this explicit user action
                        // (see PaymentNotificationListenerService.forceRecheckNow's doc comment) — no
                        // longer relies on wiping the whole processed-keys history + hoping the OS honors
                        // a rebind request, which this OEM can silently block outright.
                        PaymentNotificationListenerService.forceRecheckNow(context)
                        Toast.makeText(context, languageManager.getString("force_check_notifications_started"), Toast.LENGTH_SHORT).show()
                        forceCheckOnCooldown = true
                        scope.launch {
                            delay(FORCE_CHECK_COOLDOWN_MILLIS)
                            forceCheckOnCooldown = false
                        }
                    }
                },
                enabled = !forceCheckOnCooldown,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(languageManager.getString("force_check_notifications_button"))
            }

        }

        SettingsSectionCard(languageManager.getString("payment_source_apps_label")) {
            Text(
                languageManager.getString("payment_source_apps_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            LauncherAppsCache.scan(context)
                        }
                        installedApps = LauncherAppsCache.cachedApps
                        settingsRepo.setAppCache(LauncherAppsCache.toJsonCache())
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(languageManager.getString("rescan_apps"))
            }
            if (installedApps.isEmpty()) {
                // Confirmed on-device: LauncherAppsCache.scan() (getInstalledApplications) is correctly
                // implemented and QUERY_ALL_PACKAGES is granted at the stock-Android level, but this
                // still comes back empty on Honor/MagicOS — logcat shows a separate OEM-only gate,
                // ApplicationPackageManager.checkGetInstalledAppsPermissionStatus, denying the request.
                // That toggle isn't exposed via a stable public Intent action, so the most reliable thing
                // this app can do is point the user at its own App Info page, where this OEM surfaces the
                // "Get installed apps" permission under Permissions / Other permissions.
                Text(
                    languageManager.getString("installed_apps_empty_warning"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "Couldn't open system settings", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(languageManager.getString("open_app_permissions_button"))
                }
            }
            AppPickerCard(
                apps = installedApps,
                selectedPackages = paymentSourcePackages.toList(),
                onApply = { updated -> stateManager.setPaymentSourcePackages(updated.toSet()) },
                strings = appPickerStrings,
                modifier = Modifier.fillMaxWidth(),
                label = languageManager.getString("payment_source_apps_label"),
                starredPackages = bankingSourcePackages,
                onApplyStarred = { updated -> stateManager.setBankingSourcePackages(updated) }
            )

        }

        SettingsSectionCard(languageManager.getString("notification_model_use_label")) {
            Text(
                languageManager.getString("notification_model_use_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val notificationModes = listOf(
                ExpensesSettings.NOTIFICATION_MODEL_FULL to "notification_model_full",
                ExpensesSettings.NOTIFICATION_MODEL_NONE to "notification_model_none"
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((mode, key) in notificationModes) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { stateManager.setNotificationModelUse(mode) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = notificationModelUse == mode,
                            onClick = { stateManager.setNotificationModelUse(mode) }
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
        }

        SettingsSectionCard(languageManager.getString("capture_amountless_label")) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        languageManager.getString("capture_amountless_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = captureAmountlessPayments,
                    onCheckedChange = { stateManager.setCaptureAmountlessPayments(it) }
                )
            }
        }

        // Only where it can act. With the text going to a model, the model answers the direction
        // and this never runs — showing it there would offer a choice with no effect.
        if (notificationModelUse == ExpensesSettings.NOTIFICATION_MODEL_NONE) {
            SettingsSectionCard(languageManager.getString("notification_assumed_direction_label")) {
                Text(
                    languageManager.getString("notification_assumed_direction_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val assumptions = listOf(
                    ExpensesSettings.ASSUME_NOTHING to "notification_assume_nothing",
                    ExpensesSettings.ASSUME_OUTGOING to "notification_assume_outgoing",
                    ExpensesSettings.ASSUME_INCOMING to "notification_assume_incoming"
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((mode, key) in assumptions) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { stateManager.setNotificationAssumedDirection(mode) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = notificationAssumedDirection == mode,
                                onClick = { stateManager.setNotificationAssumedDirection(mode) }
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
            }
        }

        SettingsSectionCard(languageManager.getString("auto_accept_notification_expenses_label")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    languageManager.getString("auto_accept_notification_expenses_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoAcceptNotificationExpenses,
                    onCheckedChange = { stateManager.setAutoAcceptNotificationExpenses(it) }
                )
            }
        }

        SettingsSectionCard(languageManager.getString("dismiss_on_capture_label")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    languageManager.getString("dismiss_on_capture_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = dismissNotificationOnCapture,
                    onCheckedChange = { stateManager.setDismissNotificationOnCapture(it) }
                )
            }
        }

        val provided = remember(settings) { FieldVocabularies.provided(context) }
        // Beside the vocabularies but deliberately unlike them: an account is read from a format,
        // never learned, so this card has no supplied list and nothing to switch off term by term.
        val accounts by stateManager.bankAccountsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        BankAccountsSettingsCard(
            accounts = accounts,
            autoCreateFromScans = settings.autoCreateAccountsFromScans,
            autoCreateFromNotifications = settings.autoCreateAccountsFromNotifications,
            knownCurrencies = remember(accounts, settings.defaultCurrency) {
                (accounts.map { it.currencyCode } + settings.defaultCurrency)
                    .filter { it.isNotBlank() }.distinct().sorted()
            },
            onAutoCreateFromScansChange = { stateManager.setAutoCreateAccountsFromScans(it) },
            onAutoCreateFromNotificationsChange = { stateManager.setAutoCreateAccountsFromNotifications(it) },
            onUpdate = { stateManager.updateBankAccount(it) },
            onDelete = { stateManager.deleteBankAccount(it) },
            // The same list the classifier reads a message's issuer with, so an account's bank and
            // a capture's bank cannot drift into being two different vocabularies.
            bankNames = remember(provided.banks, settings.customBanks, settings.disabledBanks) {
                FieldVocabularies.merge(provided.banks, settings.customBanks, settings.disabledBanks)
            },
            onAddBank = { name -> scope.launch { stateManager.addVocabularyTerm(FieldVocabularies.VOCAB_BANK, name) } },
            onAdd = { typed ->
                stateManager.addTypedBankAccount(
                    typed,
                    settings.defaultAccountCurrency.ifBlank { settings.defaultCurrency }
                )
            }
        )

        // First of the three, because it is the one that decides whether the others ever run.
        VocabularySettingsCard(
            provided = provided.stopWords,
            custom = settings.customStopWords,
            disabledKeys = settings.disabledStopWords,
            vocabulary = FieldVocabularies.VOCAB_STOP,
            title = languageManager.getString("vocabulary_stop_title"),
            description = languageManager.getString("vocabulary_stop_desc"),
            stateManager = stateManager,
            languageManager = languageManager
        )
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

        if (pendingEntries.isNotEmpty()) {
            SettingsSectionCard(languageManager.getString("pending_notification_expenses_title")) {
                pendingEntries.forEach { entry ->
                    // A capture whose message never said how much: everything else is known, so the
                    // entry is worth keeping and the one missing figure is asked for here rather
                    // than the whole thing being thrown away.
                    var typedAmount by remember(entry.id) { mutableStateOf("") }
                    val amount = entry.totalAmount ?: typedAmount.trim().replace(',', '.').toDoubleOrNull()
                    // What this entry could teach, once somebody says so. Offered only where the
                    // word is genuinely unknown to the lists — a bank already listed has nothing to
                    // learn, and a merchant the app resolved was never in doubt.
                    // A name already accepted under another spelling is renamed rather than listed:
                    // a second entry for a shop the list already names is what makes the list a
                    // worse copy of the ledger. So a rename, where there is one, replaces the offer.
                    val bankToLearn = entry.bank?.takeIf { b ->
                        entry.bankRenameTo == null && FieldVocabularies.rejectionFor(
                            b, FieldVocabularies.VOCAB_BANK, context, settings
                        ) == null
                    }
                    val vendorToLearn = entry.vendorCandidate?.takeIf { v ->
                        entry.vendor == null && entry.vendorRenameTo == null &&
                            FieldVocabularies.rejectionFor(
                                v, FieldVocabularies.VOCAB_VENDOR, context, settings
                            ) == null
                    }
                    var learnBank by remember(entry.id) { mutableStateOf(false) }
                    var learnVendor by remember(entry.id) { mutableStateOf(false) }
                    var renameVendor by remember(entry.id) { mutableStateOf(false) }
                    var renameBank by remember(entry.id) { mutableStateOf(false) }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                entry.title?.takeIf { it.isNotBlank() } ?: entry.vendor ?: "—",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                (entry.totalAmount?.let { formatAmount(it, entry.currency) }
                                    ?: languageManager.getString("notification_amount_unknown")) +
                                    (entry.bank?.let { " · $it" } ?: "") +
                                    (entry.category?.let { " · $it" } ?: "") +
                                    " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.capturedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Amber, because the app is asking rather than offering: nothing
                            // identified these, and accepting one teaches the lists permanently.
                            if (bankToLearn != null || vendorToLearn != null ||
                                entry.vendorRenameTo != null || entry.bankRenameTo != null
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    entry.vendorRenameTo?.let { to ->
                                        VoxSuggestionChip(
                                            label = languageManager.getString("rename_chip")
                                                .format(entry.vendorSpelling().orEmpty(), to),
                                            asking = true,
                                            selected = renameVendor,
                                            leading = if (renameVendor) {
                                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null,
                                            onClick = { renameVendor = !renameVendor }
                                        )
                                    }
                                    entry.bankRenameTo?.let { to ->
                                        VoxSuggestionChip(
                                            label = languageManager.getString("rename_chip")
                                                .format(entry.bank.orEmpty(), to),
                                            asking = true,
                                            selected = renameBank,
                                            leading = if (renameBank) {
                                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null,
                                            onClick = { renameBank = !renameBank }
                                        )
                                    }
                                    vendorToLearn?.let {
                                        VoxSuggestionChip(
                                            label = languageManager.getString("learn_vendor_chip").format(it),
                                            asking = true,
                                            selected = learnVendor,
                                            leading = if (learnVendor) {
                                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null,
                                            onClick = { learnVendor = !learnVendor }
                                        )
                                    }
                                    bankToLearn?.let {
                                        VoxSuggestionChip(
                                            label = languageManager.getString("learn_bank_chip").format(it),
                                            asking = true,
                                            selected = learnBank,
                                            leading = if (learnBank) {
                                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null,
                                            onClick = { learnBank = !learnBank }
                                        )
                                    }
                                }
                            }
                            if (entry.totalAmount == null) {
                                OutlinedTextField(
                                    value = typedAmount,
                                    onValueChange = { typedAmount = it },
                                    label = { Text(languageManager.getString("notification_enter_amount")) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { stateManager.dismissNotificationExpense(entry.id) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(languageManager.getString("dismiss_button"))
                                }
                                Button(
                                    onClick = {
                                        stateManager.approveNotificationExpense(
                                            entry, context, amount,
                                            learnBank = bankToLearn?.takeIf { learnBank },
                                            learnVendor = vendorToLearn?.takeIf { learnVendor },
                                            renameVendor = renameVendor,
                                            renameBank = renameBank
                                        )
                                    },
                                    // Nothing to approve until there is a figure: an expense of
                                    // nothing is a gap wearing the shape of a record.
                                    enabled = amount != null && amount > 0.0,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(languageManager.getString("approve_button"))
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { stateManager.dismissAllNotificationExpenses() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(languageManager.getString("dismiss_all_button"))
                }
            }
        }
    }
}

