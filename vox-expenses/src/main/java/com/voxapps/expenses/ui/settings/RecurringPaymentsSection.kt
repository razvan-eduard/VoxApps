package com.voxapps.expenses.ui.settings

import androidx.compose.ui.platform.LocalContext
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.onboarding.VoxHintKeys
import com.voxapps.onboarding.VoxHintDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.expenses.data.RecurringPayment
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.domain.recurring.RecurringReminders
import com.voxapps.expenses.state.ExpensesStateManager

/**
 * Payments that come back: what the app has noticed, and what you have said about it.
 *
 * Observations and arrangements get separate cards because they are not the same claim. An
 * observation is the app saying "this happened twice"; an arrangement is you saying "this recurs".
 * Only the second one predicts anything, and one undifferentiated list would blur exactly the line
 * the whole feature is careful about.
 */
@Composable
fun RecurringPaymentsSection(
    payments: List<RecurringPayment>,
    threshold: Int,
    stateManager: ExpensesStateManager,
    languageManager: LanguageManager,
    notificationSettings: @Composable () -> Unit
) {
    VoxHintDialog(
        store = (LocalContext.current.applicationContext as ExpensesApplication).container.hintStore,
        hintKey = VoxHintKeys.RECURRING,
        title = languageManager.getString("hint_recurring_title"),
        body = languageManager.getString("hint_recurring_body"),
        okLabel = languageManager.getString("hint_ok"),
        dontShowAgainLabel = languageManager.getString("hint_dont_show_again")
    )

    val proposals = payments.filter { !it.confirmed && it.occurrences >= threshold && threshold > 0 }
    val arrangements = payments.filter { it.confirmed }

    SettingsSectionCard(languageManager.getString("recurring_proposals_setting")) {
        Text(
            languageManager.getString("recurring_threshold_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpensesSettings.RECURRING_THRESHOLD_CHOICES.forEach { times ->
                FilterChip(
                    selected = threshold == times,
                    onClick = { stateManager.setRecurringProposalThreshold(times) },
                    label = {
                        Text(
                            if (times == 0) languageManager.getString("recurring_threshold_off")
                            else times.toString()
                        )
                    }
                )
            }
        }
    }

    notificationSettings()

    if (proposals.isNotEmpty()) {
        SettingsSectionCard(languageManager.getString("recurring_proposals_label")) {
            proposals.forEachIndexed { index, payment ->
                if (index > 0) HorizontalDivider()
                Text(payment.vendorLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    languageManager.getString("recurring_seen_times")
                        .format(payment.occurrences, payment.dueDayOfMonth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { stateManager.dismissRecurringPayment(payment.id) },
                        modifier = Modifier.weight(1f)
                    ) { Text(languageManager.getString("dismiss_button")) }
                    Button(
                        onClick = { stateManager.confirmRecurringPayment(payment.id) },
                        modifier = Modifier.weight(1f)
                    ) { Text(languageManager.getString("recurring_confirm_button")) }
                }
            }
        }
    }

    if (arrangements.isNotEmpty()) {
        SettingsSectionCard(languageManager.getString("recurring_confirmed_label")) {
            arrangements.forEach { payment ->
                // A run of missed cycles is named rather than acted on. At the threshold the app says
                // what it thinks — this looks finished — and stops there: it took a person to say the
                // payment recurs, so it takes a person to say it no longer does.
                val goneQuiet = threshold > 0 && payment.missedCycles >= threshold
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(payment.vendorLabel, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                // Numberless on purpose: this line is reachable at any count, and a
                                // "%d due dates" template cannot agree in every language it is shown in.
                                goneQuiet -> languageManager.getString("recurring_gone_quiet")
                                payment.missedCycles >= 1 ->
                                    languageManager.counted("recurring_missed_cycles", payment.missedCycles)
                                else -> languageManager.getString("recurring_due_day")
                                    .format(payment.dueDayOfMonth)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (payment.missedCycles > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (goneQuiet) {
                        Button(onClick = { stateManager.dismissRecurringPayment(payment.id) }) {
                            Text(languageManager.getString("recurring_stop_button"))
                        }
                    } else {
                        OutlinedButton(onClick = { stateManager.dismissRecurringPayment(payment.id) }) {
                            Text(languageManager.getString("recurring_stop_button"))
                        }
                    }
                }
            }
        }
    }
}

/** The on/off for this particular reminder, carried inside the notification card's own `extra` slot
 *  so it sits with the sound and vibration it governs rather than in a section of its own. */
@Composable
fun RecurringReminderRow(
    enabled: Boolean,
    stateManager: ExpensesStateManager,
    languageManager: LanguageManager
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                languageManager.getString("recurring_reminders_label"),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                languageManager.getString("recurring_reminders_desc")
                    .format(RecurringReminders.NOTICE_DAYS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { stateManager.setRecurringRemindersEnabled(it) }
        )
    }
}
