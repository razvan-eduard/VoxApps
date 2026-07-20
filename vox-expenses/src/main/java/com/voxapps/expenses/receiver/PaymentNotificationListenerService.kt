package com.voxapps.expenses.receiver

import android.app.Notification
import android.content.ComponentName
import android.content.Context
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
        activeInstance = this
        CoroutineScope(Dispatchers.IO).launch {
            activeNotifications?.forEach { sbn -> processNotification(sbn) }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (activeInstance === this) activeInstance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) activeInstance = null
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

    private suspend fun processNotification(sbn: StatusBarNotification, force: Boolean = false) {
        val container = (applicationContext as ExpensesApplication).container
        val settings = container.settingsRepository.getSnapshot()
        if (sbn.packageName !in settings.paymentSourcePackages) return
        if (!force && processedKeys.isProcessed(sbn.key)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // Deterministic, not a guess: the user explicitly starred this exact package as a bank app.
        // LauncherAppsCache is only a display-name convenience cache (persisted from whatever the
        // device's app list looked like at the last scan) — it can legitimately miss a package that
        // was starred without ever triggering a rescan since. Rather than silently losing the bank
        // name in that case (the observed failure mode: an empty "bank" field despite the source
        // package being correctly starred), fall back to a live PackageManager label lookup, which
        // has no staleness window at all.
        val knownBankName = if (sbn.packageName in settings.bankingSourcePackages) {
            LauncherAppsCache.cachedApps.find { it.packageName == sbn.packageName }?.displayName
                ?: runCatching {
                    val pm = applicationContext.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrNull()
        } else {
            null
        }
        Logger.d(TAG, "Captured notification from ${sbn.packageName}, forwarding for LLM triage (knownBankName=$knownBankName)")
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
        // knownBankName rides along the same way rather than trusting the LLM to echo it back
        // verbatim in its JSON reply — that's what the "bank" field being empty on a successfully
        // created expense turned out to trace back to (see LlmResultReceiver). Empty segment (not
        // omitted — taskParts.getOrNull(2) must stay index-stable) when there's no known bank.
        val encodedBank = knownBankName?.let { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }.orEmpty()
        val payload = VoxLlmRequest(
            sourcePackage = packageName,
            task = "${LlmTasks.NOTIFICATION_EXPENSE_PARSE}:$encodedKey:$encodedBank",
            promptText = promptText,
            data = listOfNotNull(title, text)
        ).toJson()
        sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }

    companion object {
        // Set/cleared alongside the connect/disconnect lifecycle above — lets a "force check" action
        // reach the live instance directly instead of going through requestRebind() (which asks the
        // OS to unbind+rebind and just hopes onListenerConnected's catch-up scan follows; on this
        // OEM that rebind can be silently blocked entirely — "Service starting has been prevented by
        // iaware or trustsbase" — so it's not a reliable way to force anything).
        @Volatile private var activeInstance: PaymentNotificationListenerService? = null

        /**
         * Re-evaluates every notification currently in the shade against [processNotification],
         * bypassing the [ProcessedNotificationKeysStore] "already processed" guard entirely — unlike
         * the normal catch-up paths ([onListenerConnected]/[onNotificationRemoved]), which must
         * respect it to avoid endlessly re-parsing the same notification, an explicit user-triggered
         * "force check" tap means they specifically want a re-check regardless of prior outcome
         * (e.g. a notification that was captured but produced no expense for reasons since fixed).
         * Falls back to [requestRebind] only if the service isn't currently bound at all, so its own
         * next natural connect at least gets a normal (non-bypassing) catch-up scan.
         */
        fun forceRecheckNow(context: Context) {
            val instance = activeInstance
            if (instance != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    instance.activeNotifications?.forEach { sbn -> instance.processNotification(sbn, force = true) }
                }
            } else {
                requestRebind(ComponentName(context, PaymentNotificationListenerService::class.java))
            }
        }
    }
}
