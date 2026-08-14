package com.voxapps.expenses.receiver

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.apps.LauncherAppsCache
import com.voxapps.expenses.domain.llm.LlmTasks
import com.voxapps.expenses.domain.llm.NotificationExpenseParsePromptBuilder
import com.voxapps.expenses.domain.llm.toJsonValue
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.ipc.VoxCapabilityClient
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "PaymentNotificationListenerService"

/**
 * Opt-in capture of payment-app notifications ("you paid X at Y") into a pending expense for review.
 * Being a [NotificationListenerService] grants visibility into EVERY notification on the device — the
 * OS permission (granted once via Settings, see the "Notification capture" settings tab) can't scope
 * that down — so this class enforces its own narrower scope: [processNotification] returns
 * immediately for any package not in the user's explicit `paymentSourcePackages` allowlist (empty by
 * default), before touching the notification's content at all.
 *
 * A matched notification's title/text is forwarded to Commander's generic LLM hook (task
 * [LlmTasks.NOTIFICATION_EXPENSE_PARSE]) via [com.voxapps.ipc.VoxLlmRequestQueue.enqueueAndSend] to
 * decide whether it's actually a transaction and, if so, extract it — never parsed or acted on
 * locally. The async reply lands in [LlmResultReceiver], which either stores it for individual
 * approve/dismiss or (if `autoAcceptNotificationExpenses` is on) inserts it directly — this service
 * never creates an expense itself either way.
 *
 * [ProcessedNotificationKeysStore.markProcessed] is deliberately NOT called here, only once
 * [LlmResultReceiver] actually receives Commander's reply (the notification's key rides along
 * base64-encoded in the request's `task` string, the same "task:extra" convention
 * [com.voxapps.expenses.domain.llm.LlmTasks.EXPENSE_SCAN_CLEANUP] already uses for its imageName).
 * This guard is now backstopped by [com.voxapps.ipc.VoxLlmRequestQueue]'s own durable row (a second,
 * independent layer: this key-store guard exists to avoid *redundant* triage of the same still-shade
 * notification across `onNotificationPosted`/`onListenerConnected`/`onNotificationRemoved`, while the
 * queue's row exists to *recover* a request whose broadcast never got a reply at all — e.g. a real
 * missed Revolut charge that outlived several catch-up retries, because Commander simply wasn't
 * reachable at send time. See [com.voxapps.ipc.VoxLlmRequestQueue]'s doc comment for the recovery
 * mechanism.
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
        val collapsedText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // The expanded body supersedes the collapsed line when it extends it — the standard
        // relationship between the two — and the ticker rides separately: it is often the ONLY
        // place some apps put the transaction verb at all (Google Wallet's "View your purchase"),
        // and it was previously never read.
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = when {
            bigText.isNullOrBlank() -> collapsedText
            collapsedText.isNullOrBlank() || bigText.contains(collapsedText) -> bigText
            else -> "$collapsedText\n$bigText"
        }
        val ticker = sbn.notification.tickerText?.toString()?.takeIf { it.isNotBlank() && it != title && it != text }
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
        // Deterministic field resolution before any model is involved — see NotificationPreParse.
        // For the bank, the notification's own text outranks the starred app's label: the label is
        // per-app while the text is per-transaction, and a wallet app relays cards from many banks
        // under one label. The label stays as the fallback for bank apps whose prose never names
        // themselves.
        val fullText = listOfNotNull(text, ticker).joinToString("\n").ifBlank { null }
        val preParse = com.voxapps.expenses.domain.llm.NotificationPreParse.parse(
            title, fullText, com.voxapps.expenses.data.FieldVocabularies.vocabularies(applicationContext)
        )
        val bankName = preParse.bank ?: knownBankName
        // The template axis: reduce the message to its template's byte-shape and ask the memory
        // whether a human has already said what this exact sentence means. A hit suppresses
        // direction from the model the same way the other resolved fields are suppressed.
        val skeleton = com.voxapps.textmatch.extract.TemplateSkeleton.of(
            title, fullText, listOfNotNull(preParse.vendor, preParse.bank)
        )
        val templateHash = com.voxapps.textmatch.extract.TemplateSkeleton.hash(skeleton)
        val inheritedDirection = container.templateDirectionMemory.lookup(templateHash)
        Logger.d(TAG, "Captured notification from ${sbn.packageName}, forwarding for LLM triage " +
            "(bank=$bankName preAmount=${preParse.amount != null} preVendor=${preParse.vendor != null}" +
            " templateDirection=${inheritedDirection ?: "-"})")
        val existingCategories = container.expensesRepository.categories.first().map { it.name }
        val isLocalEngine = VoxCapabilityClient.isLocalEngine(applicationContext)
        val promptText = NotificationExpenseParsePromptBuilder.build(
            notificationTitle = title,
            notificationText = fullText,
            existingCategories = existingCategories,
            defaultCurrency = settings.defaultCurrency,
            languageCode = settings.language,
            knownBankName = bankName,
            isLocalEngine = isLocalEngine,
            preParsedAmount = preParse.amount,
            preParsedVendor = preParse.vendor,
            preParsedDirection = inheritedDirection?.toJsonValue()
        )
        val encodedKey = Base64.encodeToString(sbn.key.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // knownBankName rides along the same way rather than trusting the LLM to echo it back
        // verbatim in its JSON reply — that's what the "bank" field being empty on a successfully
        // created expense turned out to trace back to (see LlmResultReceiver). Empty segment (not
        // omitted — taskParts.getOrNull(2) must stay index-stable) when there's no known bank.
        val encodedBank = bankName?.let { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }.orEmpty()
        // enqueueAndSend persists this request (and appends its own trailing requestId segment to
        // the task string, after encodedBank) before attempting delivery. The flag alone isn't
        // enough here — not because it fails to wake a stopped app (it does; see
        // VoxAppsDiscovery.ping) but because this send is fire-and-forget: nothing tells us the
        // reply never came. See VoxLlmRequestQueue's doc comment.
        val requestId = container.pendingLlmRequestQueue.enqueueAndSend(
            context = applicationContext,
            sourcePackage = packageName,
            task = "${LlmTasks.NOTIFICATION_EXPENSE_PARSE}:$encodedKey:$encodedBank",
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            data = listOfNotNull(title, fullText)
        )
        // Suppressed fields must survive the round trip — absent from the reply by design, they are
        // reunited with it by request id, same as the scan path's date/total.
        container.scanPreParseRepository.put(
            requestId,
            com.voxapps.expenses.domain.llm.ScanPreParse(
                total = preParse.amount,
                vendor = preParse.vendor,
                direction = inheritedDirection?.toJsonValue(),
                templateHash = templateHash
            )
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
