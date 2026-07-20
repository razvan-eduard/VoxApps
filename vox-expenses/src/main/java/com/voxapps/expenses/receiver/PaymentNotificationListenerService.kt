package com.voxapps.expenses.receiver

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Base64
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
 * that down — so this class enforces its own narrower scope: [processNotification] returns
 * immediately for any package not in the user's explicit `paymentSourcePackages` allowlist (empty by
 * default), before touching the notification's content at all.
 *
 * A matched notification's title/text is forwarded to Commander's generic LLM hook (task
 * [LlmTasks.NOTIFICATION_EXPENSE_PARSE]) to decide whether it's actually a transaction and, if so,
 * extract it — never parsed or acted on locally. The async reply lands in [LlmResultReceiver], which
 * either stores it for individual approve/dismiss or (if `autoAcceptNotificationExpenses` is on)
 * inserts it directly — this service never creates an expense itself either way.
 *
 * [ProcessedNotificationKeysStore.markProcessed] is deliberately NOT called here, only once
 * [LlmResultReceiver] actually receives Commander's reply (the notification's key rides along
 * base64-encoded in the request's `task` string, the same "task:extra" convention
 * [com.voxapps.expenses.domain.llm.LlmTasks.EXPENSE_SCAN_CLEANUP] already uses for its imageName).
 * Marking it here, at dispatch time, would have meant a broadcast that's silently dropped (Commander
 * not running, killed mid-processing, no reply ever arrives) permanently "processed" this
 * notification with no expense ever created and no way to retry — exactly what happened to a real
 * missed Revolut charge that outlived several `onListenerConnected()`/`onNotificationRemoved()`
 * retries, because all of them share this same guard.
 */
class PaymentNotificationListenerService : NotificationListenerService() {

    private val processedKeys by lazy { ProcessedNotificationKeysStore(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        CoroutineScope(Dispatchers.IO).launch { processNotification(sbn) }
    }

    /**
     * Fires whenever the OS (re)binds this service — including after the process was killed while
     * backgrounded and later restarted (confirmed happening on-device: this device's OEM logs
     * "AppFastHibernation ... fast F_Z" for this app while backgrounded, and NotificationListenerService
     * is an ordinary bound service, not exempt from that). [onNotificationPosted] only fires for NEW
     * postings after the service reconnects — anything posted during the gap is only recoverable via
     * [getActiveNotifications], which returns whatever's still visible in the shade right now.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        CoroutineScope(Dispatchers.IO).launch {
            activeNotifications?.forEach { sbn -> processNotification(sbn) }
        }
    }

    /**
     * Last chance: a notification being dismissed (swiped away, auto-cancelled, tapped, cleared by
     * "Clear all", ...) is the final point [sbn]'s content is still reachable at all — after this,
     * neither [onNotificationPosted] nor [onListenerConnected]'s [getActiveNotifications] scan can
     * ever see it again. [processNotification]'s own [ProcessedNotificationKeysStore] guard makes
     * this safe to call unconditionally: if [onNotificationPosted] already captured this exact
     * [StatusBarNotification.getKey], this is a silent no-op; if the OEM-kill gap (see
     * [onListenerConnected]'s doc comment) meant it never got captured at all, this is the only
     * remaining opportunity to.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        CoroutineScope(Dispatchers.IO).launch { processNotification(sbn) }
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val container = (applicationContext as ExpensesApplication).container
        val settings = container.settingsRepository.getSnapshot()
        if (sbn.packageName !in settings.paymentSourcePackages) return
        if (processedKeys.isProcessed(sbn.key)) return

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
        val existingCategories = container.expensesRepository.categories.first().map { it.name }
        val promptText = NotificationExpenseParsePromptBuilder.build(
            notificationTitle = title,
            notificationText = text,
            existingCategories = existingCategories,
            defaultCurrency = settings.defaultCurrency,
            languageCode = settings.language,
            knownBankName = knownBankName
        )
        val encodedKey = Base64.encodeToString(sbn.key.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val payload = VoxLlmRequest(
            sourcePackage = packageName,
            task = "${LlmTasks.NOTIFICATION_EXPENSE_PARSE}:$encodedKey",
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
