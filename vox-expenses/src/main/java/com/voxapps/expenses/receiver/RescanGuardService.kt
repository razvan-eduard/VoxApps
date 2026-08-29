package com.voxapps.expenses.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.ui.formatAmount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * The standing presence behind the whole notification-capture feature: a foreground service whose
 * one job is to keep this app — and with it its notification listener — alive against an OEM eager
 * to hibernate backgrounded processes. That is the difference between a payment caught live and one
 * only recoverable, redacted, from the shade.
 *
 * A foreground service must show a notification, so that notification is made to earn its place:
 * today's spend, kept current off the ledger, and — when the platform has hidden a payment — a line
 * counting what waits, with a Rescan action to read it back off the screen. The total is not why the
 * service exists; it is what goes on the surface the service needs anyway.
 */
class RescanGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground within the grant window, then let the ledger fill the real content in.
        startForegroundCompat(build(Content(0.0, 0.0, 0.0, 0.0, 0, "", 0, 0)))
        observe()
        return START_STICKY
    }

    private fun observe() {
        val container = (applicationContext as ExpensesApplication).container
        scope.launch {
            // Settings ride in the combine too, so flipping a content switch redraws the panel at
            // once rather than waiting for the ledger to move next. The sums convert in the
            // collector (a suspend context), so the transform stays a plain function.
            combine(
                container.expensesRepository.expenses,
                container.pendingNotificationExpenseRepository.pendingFlow,
                container.settingsRepository.settingsFlow
            ) { expenses, pending, settings -> Triple(expenses, pending, settings) }
                .collect { (expenses, pending, settings) ->
                    val content = compute(container, expenses, pending, settings)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(content))
                }
        }
    }

    /**
     * The dashboard figures, each mixed-currency expense converted into the home currency the same
     * way the reports and the widget do — not filtered to one currency, which would drop whatever
     * was paid in another. Same-currency is a no-op, so the common single-currency ledger costs no
     * conversion at all.
     */
    private suspend fun compute(
        container: com.voxapps.expenses.di.ExpensesContainer,
        expenses: List<com.voxapps.expenses.data.Expense>,
        pending: List<com.voxapps.expenses.domain.llm.PendingNotificationExpense>,
        settings: com.voxapps.expenses.data.preferences.ExpensesSettings
    ): Content {
        val home = settings.homeCurrency
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfWeek = today.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        suspend fun sumSince(from: Long, dir: TransactionDirection): Double {
            var total = 0.0
            for (e in expenses) {
                if (e.direction != dir || e.dateTime < from) continue
                total += container.exchangeRateRepository.convertToHome(e.totalAmount, e.currencyCode, home) ?: 0.0
            }
            return total
        }
        return Content(
            today = sumSince(startOfToday, TransactionDirection.OUTGOING),
            week = sumSince(startOfWeek, TransactionDirection.OUTGOING),
            month = sumSince(startOfMonth, TransactionDirection.OUTGOING),
            todayIncome = sumSince(startOfToday, TransactionDirection.INCOMING),
            todayCount = expenses.count { it.direction == TransactionDirection.OUTGOING && it.dateTime >= startOfToday },
            currency = home,
            reviewCount = pending.size,
            redactedStubs = pending.count { it.redactedStub }
        )
    }

    private data class Content(
        val today: Double, val week: Double, val month: Double,
        val todayIncome: Double, val todayCount: Int, val currency: String,
        val reviewCount: Int, val redactedStubs: Int
    )

    private fun build(c: Content): android.app.Notification {
        val app = applicationContext as ExpensesApplication
        val lang = app.container.languageManager
        val settings = app.container.settingsRepository.getSnapshot()
        ensureChannel()

        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        fun money(v: Double) = if (c.currency.isBlank()) "" else formatAmount(v, c.currency)
        // Each line is independent — the person chose which of them earn a place. The rescan line is
        // not one of those: it is an action waiting, and it shows itself whenever something waits.
        val lines = buildList {
            if (settings.notifShowToday) add(lang.getString("guard_fg_today").format(money(c.today)))
            if (settings.notifShowTodayCount) add(lang.counted("guard_fg_count", c.todayCount))
            if (settings.notifShowTodayIncome) add(lang.getString("guard_fg_income").format(money(c.todayIncome)))
            if (settings.notifShowWeek) add(lang.getString("guard_fg_week").format(money(c.week)))
            if (settings.notifShowMonth) add(lang.getString("guard_fg_month").format(money(c.month)))
            if (settings.notifShowReviewCount && c.reviewCount > 0) add(lang.counted("guard_fg_review", c.reviewCount))
            if (c.redactedStubs > 0) add(lang.counted("rescan_persistent_body", c.redactedStubs))
        }
        val body = lines.joinToString("\n").ifBlank { lang.getString("guard_fg_idle") }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(lang.getString("guard_fg_title"))
            .setContentText(lines.firstOrNull() ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (c.redactedStubs > 0) {
            val rescan = PendingIntent.getBroadcast(
                this, 1,
                Intent(this, ExpenseActionReceiver::class.java).setAction(RescanGuard.ACTION_RESCAN),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, lang.getString("rescan_action"), rescan)
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val lang = (applicationContext as ExpensesApplication).container.languageManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, lang.getString("guard_fg_channel"), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "guard_presence"
        private const val NOTIFICATION_ID = 0x2E5CB
    }
}
