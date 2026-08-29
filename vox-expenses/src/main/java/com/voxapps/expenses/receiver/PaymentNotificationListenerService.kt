package com.voxapps.expenses.receiver

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.preferences.knownCurrencies
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
 * never decides for itself what becomes of a capture: it hands the sentence to
 * [com.voxapps.recordflow.RecordFlow], which reads what the device can establish and then either
 * files the expense, leaves it in the review queue, or asks — the last only where the level allows
 * it. See [com.voxapps.expenses.domain.llm.NotificationExpenseFlow].
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
        // A group summary is a container, not a message. The OS posts one whenever several
        // notifications from the same app pile up, and it carries their count rather than their
        // text — so reading it as a capture reads nothing, every time, and the discard it produces
        // is indistinguishable in a log from a real message that could not be read.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
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
        // What the platform's code-protection guard delivers to an unprivileged listener: the body
        // withheld, the title kept or replaced with one fixed system sentence. The content is gone
        // by design and nothing here can recover it — but the gutted shape is recognisable, and a
        // capture recognised as gutted waits in review as a figure to fill in rather than
        // vanishing without a trace.
        val redactedMessage = redactedNotificationMessage()
        val cleanTitle = title?.takeUnless { it == redactedMessage }
        val cleanText = text?.takeUnless { it == redactedMessage }
        val redacted = (redactedMessage != null && (title == redactedMessage || text == redactedMessage)) ||
            (!cleanTitle.isNullOrBlank() && cleanText.isNullOrBlank())
        if (!redacted && cleanTitle.isNullOrBlank() && cleanText.isNullOrBlank()) return

        // A refusal is not a transaction. Before any field is read, any template is looked up or any
        // sentence is sent anywhere: if the message carries a word from the stop list, this app has
        // nothing to file. Everything downstream — the pre-parse, the template memory, the model,
        // the review queue — exists to work out what a payment was, and a declined card is not a
        // payment that needs working out.
        //
        // Marked handled so the same message is not reconsidered on every rebind, and deliberately
        // never dismissed: a refusal is exactly the kind of message you still want to see.
        val stopWord = com.voxapps.textmatch.extract.VocabularyClassifier.firstTerm(
            listOfNotNull(title, text, ticker).joinToString("\n"),
            com.voxapps.expenses.data.FieldVocabularies.stopWords(applicationContext, settings)
        )
        if (stopWord != null) {
            Logger.d(TAG, "Stopped by \"$stopWord\": ${sbn.packageName}")
            processedKeys.markProcessed(sbn.key)
            return
        }

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
        val fullText = listOfNotNull(cleanText, ticker).joinToString("\n").ifBlank { null }
        val preParse = com.voxapps.expenses.domain.llm.NotificationPreParse.parse(
            cleanTitle, fullText,
            com.voxapps.expenses.data.FieldVocabularies.vocabularies(applicationContext, settings),
            // What settles "lei" as RON rather than MDL: the currencies this device was already
            // told about, its own and its cards'.
            settings.knownCurrencies(
                container.expensesRepository.bankAccounts.first().map { it.currencyCode }
            ),
            // A starred banking source is already known to be a payment, so a title-shaped merchant
            // with no designator or card to anchor it — "Café", "37,00 RON" — may take the figure as
            // its anchor. Never for an unstarred source, where the figure is too weak a signal.
            amountAnchorsVendor = sbn.packageName in settings.bankingSourcePackages
        )
        val bankName = preParse.bank ?: knownBankName
        // The template axis: reduce the message to its template's byte-shape and ask the memory
        // whether a human has already said what this exact sentence means. A hit suppresses
        // direction from the model the same way the other resolved fields are suppressed.
        val skeleton = com.voxapps.textmatch.extract.TemplateSkeleton.of(
            cleanTitle, fullText, listOfNotNull(preParse.vendor, preParse.bank)
        )
        val templateHash = com.voxapps.textmatch.extract.TemplateSkeleton.hash(skeleton)
        val inheritedDirection = container.templateDirectionMemory.lookup(templateHash)
        val paymentKnown = container.templateDirectionMemory.lookupIsPayment(templateHash)
        // Display backfill for the learned-templates settings list — capture time is the only
        // moment the hash and its text coexist (no-op unless this template is already learned).
        container.templateDirectionMemory.noteSkeleton(templateHash, skeleton)
        // One flow, whatever the level: the shared template reads what the device can establish,
        // decides who answers the rest, and either files the expense, queues it for a person, or
        // asks. The promise that nothing leaves the device is kept inside it, before a prompt is
        // ever composed — see RecordFlowPolicy.
        val captured = com.voxapps.expenses.domain.llm.CapturedNotification(
            title = cleanTitle,
            text = fullText,
            amount = preParse.amount,
            vendor = preParse.vendor,
            bank = bankName,
            currency = preParse.currency,
            templateHash = templateHash,
            direction = inheritedDirection,
            knownPayment = paymentKnown,
            fromStarredBank = sbn.packageName in settings.bankingSourcePackages,
            redacted = redacted,
            sourceKey = sbn.key
        )
        val flow = com.voxapps.expenses.domain.llm.NotificationExpenseFlow(applicationContext, container)
        val outcome = com.voxapps.recordflow.RecordFlow.dispatch(
            spec = flow,
            input = captured,
            level = com.voxapps.expenses.data.preferences.ExpensesSettings.notificationLevelOf(
                settings.notificationModelUse
            )
        ) { _, prompt ->
            sendForTriage(sbn, bankName, prompt, preParse, templateHash, inheritedDirection, paymentKnown)
        }
        // Marked once it has actually been handled. Where a request went out, the reply's own
        // arrival marks it instead, for the same reason it always has: a capture that got no answer
        // must stay eligible for the next attempt.
        if (outcome !is com.voxapps.recordflow.RecordFlow.Outcome.Asked) {
            processedKeys.markProcessed(sbn.key)
            // A gutted capture must NOT clear its source when recovery is on: the shade copy is the
            // last complete record of the payment, and the one thing the stub can still be
            // recovered from. Off, there is nothing to recover it with, so it dismisses as usual.
            if (redacted && flow.kept == com.voxapps.expenses.domain.llm.NotificationExpenseFlow.Kept.REVIEW &&
                settings.guardNotificationEnabled
            ) {
                RescanGuard.stubQueued(applicationContext)
            } else {
                maybeDismiss(sbn.key, flow.kept, settings)
            }
        }
    }

    /**
     * The sentence the platform substitutes for a notification its code-protection guard withheld,
     * in the device's own locale — read from the system's resources so the comparison never chases
     * translations or OEM rewordings. Null where this Android has no such string.
     */
    private fun redactedNotificationMessage(): String? =
        android.content.res.Resources.getSystem()
            .getIdentifier("redacted_notification_message", "string", "android")
            .takeIf { it != 0 }
            ?.let { id ->
                runCatching { android.content.res.Resources.getSystem().getString(id) }.getOrNull()
            }

    /**
     * Clears the source notification once its capture is safely somewhere, if that is what you asked
     * for.
     *
     * Gated on what was kept rather than on the parse having succeeded: a message the app read and
     * then threw away is a message you still need to see. Both keeping outcomes qualify — a record
     * and a review entry are equally "the app has this now" — and the review queue is reachable from
     * the same screen the setting lives on.
     */
    private fun maybeDismiss(
        key: String,
        kept: com.voxapps.expenses.domain.llm.NotificationExpenseFlow.Kept,
        settings: com.voxapps.expenses.data.preferences.ExpensesSettings
    ) {
        if (!settings.dismissNotificationOnCapture) return
        if (kept == com.voxapps.expenses.domain.llm.NotificationExpenseFlow.Kept.NOTHING) return
        dismissCaptured(key)
    }

    /**
     * Hand the sentence to Commander, and remember on this side what was suppressed from the
     * question so the reply can be reunited with it.
     */
    private suspend fun sendForTriage(
        sbn: StatusBarNotification,
        bankName: String?,
        promptText: String,
        preParse: com.voxapps.expenses.domain.llm.NotificationPreParse.Result,
        templateHash: String?,
        inheritedDirection: com.voxapps.expenses.data.TransactionDirection?,
        paymentKnown: Boolean
    ) {
        val container = (applicationContext as ExpensesApplication).container
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val fullText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
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
                templateHash = templateHash,
                isPaymentKnown = paymentKnown
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
         * Takes a captured notification out of the shade.
         *
         * Only the bound listener may cancel what it can see, so this goes through the live
         * instance or does nothing at all. Doing nothing is the right failure: the notification
         * stays where its sender put it, which is the state a person can still act on.
         *
         * Callers must have established that the capture was actually kept — this removes the only
         * remaining copy of a message this app did not write, and there is no undo for it.
         */
        fun dismissCaptured(key: String) {
            val service = activeInstance
            if (service == null) {
                Logger.d(TAG, "Nothing to dismiss: no bound listener")
                return
            }
            runCatching { service.cancelNotification(key) }
                .onFailure { Logger.w(TAG, "Could not dismiss the captured notification: ${it.message}") }
        }

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
