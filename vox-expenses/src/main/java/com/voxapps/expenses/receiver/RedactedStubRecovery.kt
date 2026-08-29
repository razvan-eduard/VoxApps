package com.voxapps.expenses.receiver

import android.content.Context
import android.graphics.Bitmap
import com.voxapps.expenses.ExpensesApplication
import com.voxapps.expenses.domain.llm.PendingNotificationExpense
import com.voxapps.logging.Logger
import com.voxapps.textmatch.extract.CurrencyMarkedAmounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "RedactedStubRecovery"

/**
 * Completes the stubs the code-protection guard left figureless, from what the shade renders.
 *
 * A redacted stub knows everything about its payment except the sum — the one thing the guard
 * withheld. But the person opened the shade to tap Rescan, and the shade draws the notification
 * whole, so the sum is on screen. This reads it back: the [ShadeReaderService] hands over the
 * panel's text (from the accessibility tree, or failing that the pixels), the figure beside each
 * stub's own name is taken, and the stub becomes an ordinary review entry with an amount to approve.
 *
 * Nothing is written to the ledger here — a notification was never a deliberate action, so a
 * recovered stub still waits for the person, exactly like every other notification capture.
 */
object RedactedStubRecovery {

    /** Runs the whole recovery: read the shade, fill every stub it can, dismiss what it recovered. */
    fun recover(context: Context) {
        val app = context.applicationContext as ExpensesApplication
        val service = ShadeReaderService.instance
        if (service == null) {
            Logger.w(TAG, "recover: shade reader not enabled")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val stubs = app.container.pendingNotificationExpenseRepository.snapshot()
                .filter { it.redactedStub && it.totalAmount == null }
            if (stubs.isEmpty()) {
                Logger.d(TAG, "recover: no redacted stubs waiting")
                return@launch
            }
            // Primary path: the accessibility tree's text. Fall back to a screenshot only for what
            // the tree could not fill — a stricter OEM may strip the sensitive lines from the tree
            // while still rendering them.
            service.readShadeText { lines ->
                CoroutineScope(Dispatchers.IO).launch {
                    val remaining = fillFrom(context, stubs, lines)
                    // The presence service's notification redraws itself off the pending flow that
                    // fillFrom just changed, so a filled stub drops out of its count on its own.
                    if (remaining.isNotEmpty()) {
                        Logger.d(TAG, "recover: ${remaining.size} stub(s) unfilled by tree — trying screenshot OCR")
                        service.captureShade { bmp -> recoverByOcr(context, bmp) }
                    }
                }
            }
        }
    }

    /**
     * Fills every stub whose amount can be read out of [lines], returns those it could not.
     *
     * The match is by the stub's own name: the line that carries it, and the currency-marked figure
     * on or just after it — the shade draws a payment as its title above its body, so the sum sits a
     * line or two below the name. A figure with no name to belong to is left alone.
     */
    private suspend fun fillFrom(
        context: Context,
        stubs: List<PendingNotificationExpense>,
        lines: List<String>
    ): List<PendingNotificationExpense> {
        if (lines.isEmpty()) return stubs
        val amounts = CurrencyMarkedAmounts.find(lines.joinToString("\n")).map { it.lineIndex to it.value }
        val unfilled = mutableListOf<PendingNotificationExpense>()
        val claimed = mutableSetOf<Int>() // amount indices already taken, so two stubs can't share one
        for (stub in stubs) {
            val name = stub.vendorSpelling() ?: stub.title
            val hit = matchAmount(lines, amounts, name, claimed)
            if (hit != null) {
                claimed += hit.first
                val app = context.applicationContext as ExpensesApplication
                app.container.pendingNotificationExpenseRepository.updatePending(
                    stub.copy(totalAmount = hit.second, redactedStub = false, capturedAt = stub.capturedAt)
                )
                stub.sourceKey?.let { PaymentNotificationListenerService.dismissCaptured(it) }
                Logger.d(TAG, "filled stub '${name}' with ${hit.second}")
            } else {
                unfilled += stub
            }
        }
        return unfilled
    }

    /**
     * The figure that belongs to [name], as an (amount index, value) pair, or null when none does.
     *
     * The shade draws a payment as its name above its body, so the sum sits on the name's line or a
     * few below it — never above. Pure, and separated from the writing so the heuristic is testable:
     * [amounts] is each currency-marked figure as (lineIndex, value), [claimed] the indices a
     * previous stub already took, so two identical names never fold onto one figure.
     */
    fun matchAmount(
        lines: List<String>,
        amounts: List<Pair<Int, Double>>,
        name: String?,
        claimed: Set<Int>
    ): Pair<Int, Double>? {
        val nameLine = name?.let { n -> lines.indexOfFirst { it.contains(n, ignoreCase = true) } } ?: -1
        if (nameLine < 0) return null
        return amounts.withIndex().firstOrNull { (i, a) ->
            i !in claimed && a.first in nameLine..(nameLine + WINDOW)
        }?.let { it.index to it.value.second }
    }

    /**
     * The fallback: hand the captured shade to Vision. The reply is not awaited here — it arrives
     * as a broadcast and [OcrResultReceiver] routes it to [fillFromOcrText], which re-reads the
     * still-waiting stubs from the store. A stub the tree could not fill simply stays in review
     * until then.
     */
    private fun recoverByOcr(context: Context, bmp: Bitmap?) {
        if (bmp == null) {
            Logger.w(TAG, "ocr fallback: no bitmap")
            return
        }
        ShadeOcrBridge.recognize(context, bmp)
    }

    /** Vision's answer for the shade screenshot: fill what the recognised text can, then let go of
     *  the staged image. Re-reads the store rather than trusting a carried list — the tree pass may
     *  have filled some in the meantime. */
    fun fillFromOcrText(context: Context, text: String?) {
        val app = context.applicationContext as ExpensesApplication
        CoroutineScope(Dispatchers.IO).launch {
            val stubs = app.container.pendingNotificationExpenseRepository.snapshot()
                .filter { it.redactedStub && it.totalAmount == null }
            val lines = text?.split("\n").orEmpty().map { it.trim() }.filter { it.isNotBlank() }
            val stillUnfilled = fillFrom(context, stubs, lines)
            ShadeOcrBridge.cleanup(context)
            if (stillUnfilled.isNotEmpty()) Logger.d(TAG, "ocr fallback left ${stillUnfilled.size} stub(s) unfilled")
        }
    }

    /** How many lines below a stub's name its figure may sit — a wrapped body, no more. */
    private const val WINDOW = 3
}
