package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings

/**
 * What a scan is allowed to do, in one place.
 *
 * The four settings differ along three axes and nothing else: whether anything is sent at all,
 * whether the line items are asked for, and whether the answer is applied or offered. Spelling that
 * out here rather than at each call site is not tidiness — the sending paths are near-identical
 * copies of one another, and when the rule for one of these axes lived inside them, one copy
 * honoured it and the other did not. A scan went to the model with the setting plainly set to
 * nothing, on a device, and only a log line gave it away.
 *
 * So each axis is asked as a question with one answer. A path that forgets to ask is a path that
 * does not compile against these names, rather than one that quietly does the wrong thing.
 */
enum class ScanMode {
    /** The model reads everything and its answer is applied. */
    FULL,

    /** Items and totals come from the deterministic reader; the model answers for the rest. */
    VENDOR_CATEGORY_AUTO,

    /** The same, except the answer arrives as something to accept rather than as a decision. */
    VENDOR_CATEGORY_SUGGEST,

    /** Nothing is sent. The record is written from what was read on the device. */
    NONE;

    /** Whether any text leaves the device for this scan. */
    val sendsToModel: Boolean get() = this != NONE

    /**
     * Whether the record is written before anything is sent.
     *
     * True for both settings that do not let the model decide: with nothing sent there is no later
     * moment to write it, and with the answer only offered the expense has to exist first for the
     * offer to be attached to.
     */
    val writesRecordLocally: Boolean get() = this == NONE || this == VENDOR_CATEGORY_SUGGEST

    /**
     * Whether the prompt asks the model to read the line items.
     *
     * Only the fullest setting does. The others promise that the items were read on the device, and
     * a promise that quietly breaks when the deterministic reading came back empty is not one —
     * an empty item list is a blank a person fills, which is the trade the whole reader makes.
     */
    val asksForItems: Boolean get() = this == FULL

    /** Whether what comes back is written into the record or offered beside it. */
    val appliesAnswer: Boolean get() = this != VENDOR_CATEGORY_SUGGEST
}

object ScanFlow {

    fun modeOf(settings: ExpensesSettings): ScanMode = when (settings.scanModelUse) {
        ExpensesSettings.SCAN_MODEL_NONE -> ScanMode.NONE
        ExpensesSettings.SCAN_MODEL_HEADER_FOOTER_AUTO -> ScanMode.VENDOR_CATEGORY_AUTO
        ExpensesSettings.SCAN_MODEL_HEADER_FOOTER_SUGGEST -> ScanMode.VENDOR_CATEGORY_SUGGEST
        // An unrecognised value reads as the fullest behaviour, which is what installs had before
        // the setting existed — a stored value from a newer build must not silently stop a scan.
        else -> ScanMode.FULL
    }

    /**
     * Whether this scan's prompt should carry the item half at all.
     *
     * Two independent reasons to leave it out, and either is enough: the user asked for the items to
     * stay on the device, or the engine said it cannot take a prompt that long. They are asked in
     * that order because the first is a decision and the second is a capability.
     */
    fun asksForItems(mode: ScanMode, engineTakesLongPrompt: Boolean): Boolean =
        mode.asksForItems && engineTakesLongPrompt
}
