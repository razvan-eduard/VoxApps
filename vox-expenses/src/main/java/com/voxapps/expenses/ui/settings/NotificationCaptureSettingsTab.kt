package com.voxapps.expenses.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.apppicker.AppPickerCard
import com.voxapps.apppicker.AppPickerStrings
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.apps.LauncherAppsCache
import com.voxapps.expenses.domain.llm.PendingNotificationExpense
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.expenses.ui.formatAmount
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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
    stateManager: ExpensesStateManager,
    settingsRepo: ExpensesSettingsRepository,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var accessGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }

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
            selected = languageManager.getString("selected"),
            setAsDefault = languageManager.getString("set_as_default"),
            removeDefault = languageManager.getString("remove_default")
        )
    }

    val pendingEntries by stateManager.pendingNotificationExpenses.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(languageManager.getString("notification_capture_title"), style = MaterialTheme.typography.titleMedium)
        Text(
            languageManager.getString("notification_capture_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            if (accessGranted) languageManager.getString("notification_access_granted")
            else languageManager.getString("notification_access_not_granted"),
            style = MaterialTheme.typography.bodyMedium,
            color = if (accessGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(languageManager.getString("grant_notification_access_button"))
        }
        Button(
            onClick = {
                accessGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(languageManager.getString("recheck_access_button"))
        }

        HorizontalDivider()

        Text(languageManager.getString("payment_source_apps_label"), style = MaterialTheme.typography.labelLarge)
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
        AppPickerCard(
            apps = installedApps,
            selectedPackages = paymentSourcePackages.toList(),
            onToggleApp = { packageName ->
                val updated = if (packageName in paymentSourcePackages) {
                    paymentSourcePackages - packageName
                } else {
                    paymentSourcePackages + packageName
                }
                stateManager.setPaymentSourcePackages(updated)
            },
            strings = appPickerStrings,
            modifier = Modifier.fillMaxWidth(),
            label = languageManager.getString("payment_source_apps_label"),
            defaultPackage = null,
            onSetDefault = null
        )

        if (pendingEntries.isNotEmpty()) {
            HorizontalDivider()
            Text(languageManager.getString("pending_notification_expenses_title"), style = MaterialTheme.typography.labelLarge)

            pendingEntries.forEach { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            entry.title?.takeIf { it.isNotBlank() } ?: entry.vendor ?: "—",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            formatAmount(entry.totalAmount, entry.currency) +
                                (entry.category?.let { " · $it" } ?: "") +
                                " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.capturedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                onClick = { stateManager.approveNotificationExpense(entry) },
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
