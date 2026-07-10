package com.voxapps.expenses.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.expenses.domain.llm.PendingNotificationExpense
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.ui.LocalLanguageManager
import com.voxapps.expenses.ui.formatAmount
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
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current

    var accessGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }

    val installedApps = remember {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }
            .sortedBy { it.second.lowercase() }
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
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(installedApps, key = { it.first }) { (packageName, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val updated = if (packageName in paymentSourcePackages) {
                                paymentSourcePackages - packageName
                            } else {
                                paymentSourcePackages + packageName
                            }
                            stateManager.setPaymentSourcePackages(updated)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = packageName in paymentSourcePackages,
                        onCheckedChange = {
                            val updated = if (it) paymentSourcePackages + packageName else paymentSourcePackages - packageName
                            stateManager.setPaymentSourcePackages(updated)
                        }
                    )
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

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
