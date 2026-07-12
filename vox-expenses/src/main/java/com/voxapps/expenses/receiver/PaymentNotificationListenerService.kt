package com.voxapps.expenses.receiver

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.apps.LauncherAppsCache
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.NotificationExpenseParsePromptBuilder
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "PaymentNotificationListenerService"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"

/**
 * Opt-in capture of payment-app notifications ("you paid X at Y") into a pending expense for review.
 * Being a [NotificationListenerService] grants visibility into EVERY notification on the device — the
 * OS permission (granted once via Settings, see the "Notification capture" settings tab) can't scope
 * that down — so this class enforces its own narrower scope: [onNotificationPosted] returns
 * immediately for any package not in the user's explicit `paymentSourcePackages` allowlist (empty by
 * default), before touching the notification's content at all.
 *
 * A matched notification's title/text is forwarded to Commander's generic LLM hook (task
 * [LlmTasks.NOTIFICATION_EXPENSE_PARSE]) to decide whether it's actually a transaction and, if so,
 * extract it — never parsed or acted on locally. The async reply lands in [LlmResultReceiver], which
 * stores it for individual approve/dismiss (see `PendingNotificationExpenseRepository`); this service
 * never creates an expense directly.
 */
class PaymentNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val container = (applicationContext as ExpensesApplication).container
        val settings = container.settingsRepository.getSnapshot()
        if (sbn.packageName !in settings.paymentSourcePackages) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // Deterministic, not a guess: the user explicitly starred this exact package as a bank app.
        val knownBankName = if (sbn.packageName in settings.bankingSourcePackages) {
            LauncherAppsCache.cachedApps.find { it.packageName == sbn.packageName }?.displayName
        } else {
            null
        }

        Logger.d(TAG, "Captured notification from ${sbn.packageName}, forwarding for LLM triage")
        CoroutineScope(Dispatchers.IO).launch {
            val existingCategories = container.expensesRepository.categories.first().map { it.name }
            val promptText = NotificationExpenseParsePromptBuilder.build(
                notificationTitle = title,
                notificationText = text,
                existingCategories = existingCategories,
                defaultCurrency = settings.defaultCurrency,
                languageCode = settings.language,
                knownBankName = knownBankName
            )
            val payload = VoxLlmRequest(
                sourcePackage = packageName,
                task = LlmTasks.NOTIFICATION_EXPENSE_PARSE,
                promptText = promptText,
                data = listOfNotNull(title, text)
            ).toJson()
            sendBroadcast(
                Intent(VoxIpc.ACTION_LLM_PROCESS)
                    .setPackage(COMMANDER_PACKAGE)
                    .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
            )
        }
    }
}
