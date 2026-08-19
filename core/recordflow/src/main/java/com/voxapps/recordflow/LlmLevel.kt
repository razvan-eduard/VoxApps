package com.voxapps.recordflow

/**
 * How much of a record a field is, in the only sense that matters when deciding whether a machine's
 * answer for it may be written without being read.
 *
 * Not a measure of importance. A record's [HEAD] can be the most important thing on it; what makes it
 * head is that a wrong answer there is obvious the moment somebody looks. A [BODY] is the opposite:
 * many entries, each entirely plausible on its own, where one wrong figure is indistinguishable from
 * a right one and nothing downstream will catch it.
 */
enum class FieldWeight {
    /** Few and coarse: a title, a vendor, a place, a category, a day's summary. */
    HEAD,

    /** Many and fine: an invoice's line items, an hourly forecast, a split of amounts. */
    BODY
}

/** How much of the input a request carries. */
enum class AskScope {
    /** No request is made. */
    NOTHING,

    /** Only the coarse fields the deterministic pass could not establish. The narrowest question
     *  that is still a question — and where it established all of them, there is none. */
    MISSING_HEAD,

    /** Every coarse field, whether or not the device had already worked it out. */
    ALL_HEAD,

    /** The whole input, fine detail included. */
    EVERYTHING;

    /** Whether a field of this [weight] is part of what gets asked about. */
    fun covers(weight: FieldWeight): Boolean = when (this) {
        NOTHING -> false
        MISSING_HEAD, ALL_HEAD -> weight == FieldWeight.HEAD
        EVERYTHING -> true
    }
}

/**
 * How far a model is let into making a record.
 *
 * Two questions, asked separately because they have always been two: **how much is sent**, and **how
 * much of what comes back is written without anyone reading it**. Every rung below is one coherent
 * pair of answers, and between them they are exhaustive — the constructor rejects the pairs that
 * cannot mean anything, so a combination is either on this list or impossible.
 *
 * The order is the order a settings screen shows: by how much is sent first, and within that, by how
 * much lands unread. Each rung includes what the one before it does.
 *
 * The levels deliberately do not describe what a model *does*. What a model contributes differs per
 * satellite — a spoken sentence is already a whole note, while the same sentence is only half an
 * expense — so a scale written in terms of the model's work cannot mean the same thing twice. What is
 * the same everywhere is who ends up answering for a field: the device, the person, or the model.
 */
enum class LlmLevel(
    val asks: AskScope,
    private val appliesHead: Boolean,
    private val appliesBody: Boolean
) {
    /**
     * Cold in both directions. Nothing is sent, and nothing is accepted back — not a value, not a
     * suggestion.
     *
     * The only rung whose promise is about the device rather than about the record, and it is kept by
     * never composing a request at all.
     */
    NONE(AskScope.NOTHING, appliesHead = false, appliesBody = false),

    /** Asks about the coarse fields it could not work out, and writes none of the answer. */
    ASSIST_SUGGEST(AskScope.MISSING_HEAD, appliesHead = false, appliesBody = false),

    /** Asks about the coarse fields it could not work out, and fills them in. */
    ASSIST_AUTO(AskScope.MISSING_HEAD, appliesHead = true, appliesBody = false),

    /** Asks about every coarse field, and writes none of the answer. */
    HEAD_SUGGEST(AskScope.ALL_HEAD, appliesHead = false, appliesBody = false),

    /** Asks about every coarse field, and fills them in. */
    HEAD_AUTO(AskScope.ALL_HEAD, appliesHead = true, appliesBody = false),

    /**
     * Sends the whole input, and writes none of the answer.
     *
     * The best answer the arrangement can produce, with nothing at all reaching the record unread.
     * Worth its own rung because "how good an answer" and "how much of it I accept sight unseen" are
     * the two questions this scale exists to separate, and this is the corner where they diverge
     * furthest.
     */
    ALL_SUGGEST(AskScope.EVERYTHING, appliesHead = false, appliesBody = false),

    /**
     * Sends the whole input, fills in the coarse fields, and leaves the fine detail to be approved.
     *
     * The rung most records actually want. The coarse fields are worth having filled and are
     * checkable at a glance; the fine ones are worth having and are exactly where an unnoticed wrong
     * figure survives.
     */
    BODY_SUGGEST(AskScope.EVERYTHING, appliesHead = true, appliesBody = false),

    /** Sends the whole input and writes all of the answer. */
    FULL(AskScope.EVERYTHING, appliesHead = true, appliesBody = true);

    init {
        require(!appliesBody || appliesHead) {
            "$name writes the fine detail but not the coarse fields, which is backwards"
        }
        require(!appliesHead || asks.covers(FieldWeight.HEAD)) {
            "$name writes an answer to a question it never asks"
        }
        require(!appliesBody || asks.covers(FieldWeight.BODY)) {
            "$name writes fine detail it never asks for"
        }
    }

    /** Whether nothing at all crosses the device's edge at this level, in either direction. */
    val staysOnDevice: Boolean get() = asks == AskScope.NOTHING

    /** Whether an answer for a field of this [weight] is written onto the record, or offered. */
    fun applies(weight: FieldWeight): Boolean = when (weight) {
        FieldWeight.HEAD -> appliesHead
        FieldWeight.BODY -> appliesBody
    }

    /** Whether anything here arrives as something to accept rather than as a written value. */
    val offersAnything: Boolean
        get() = FieldWeight.entries.any { asks.covers(it) && !applies(it) }

    companion object {
        /**
         * The rung that is this pair of answers, or null where the pair means nothing.
         *
         * The lookup exists so a screen can be built from the two questions rather than from the
         * eight names: a control per axis, and this to turn the pair back into the level that gets
         * stored. Returning null for an incoherent pair is what lets the screen grey a box rather
         * than invent a rung.
         */
        fun of(asks: AskScope, appliesHead: Boolean, appliesBody: Boolean): LlmLevel? =
            entries.firstOrNull {
                it.asks == asks &&
                    it.applies(FieldWeight.HEAD) == appliesHead &&
                    it.applies(FieldWeight.BODY) == appliesBody
            }
    }
}

/**
 * What one satellite can honestly do for one kind of input.
 *
 * [default] is what an install that never chose gets, and it is required to be a member of
 * [supported] — a default outside the supported set is the sort of contradiction that surfaces
 * months later as a flow that silently does nothing.
 */
data class FlowSupport(
    val source: RecordSource,
    val supported: Set<LlmLevel>,
    val default: LlmLevel,
    /**
     * Whether an answer can be *offered* here rather than written.
     *
     * A capability, not a preference: offering requires somewhere to put the proposal and a way to
     * accept it field by field. Only [LlmLevel.NONE] and [LlmLevel.FULL] never need it, so a
     * satellite without that surface can support only those two — enforced here rather than left to
     * be discovered later as answers that quietly applied themselves.
     */
    val suggestsAnswers: Boolean = false,
    /**
     * Which halves this record actually has.
     *
     * Not every record has both. A note's text is the record rather than a machine's answer about
     * it, so the only thing a model contributes is a title and a category — coarse fields, and
     * nothing else. Declaring that keeps the screen from offering a choice about fine detail the
     * record does not have, and keeps the two rungs that differ only in how the fine detail is
     * treated from being offered as if they differed at all.
     */
    val weights: Set<FieldWeight> = FieldWeight.entries.toSet()
) {
    init {
        require(weights.isNotEmpty()) { "$source declares a record with no fields to answer for" }
        require(FieldWeight.BODY in weights || LlmLevel.BODY_SUGGEST !in supported) {
            "$source has no fine detail, so BODY_SUGGEST is FULL under another name"
        }
        require(supported.isNotEmpty()) { "$source declares no level it can honour" }
        require(default in supported) { "$source defaults to $default, which it does not support" }
        val needSurface = supported.filter { it.offersAnything }
        require(suggestsAnswers || needSurface.isEmpty()) {
            "$source supports $needSurface but declares no way to show a suggestion"
        }
        require(!suggestsAnswers || needSurface.isNotEmpty()) {
            "$source offers to show suggestions at levels that never produce one"
        }
    }

    /** Whether this flow can run with nothing crossing the device's edge. */
    val canRunOffline: Boolean get() = LlmLevel.NONE in supported
}

/** The three ways a record starts. Named for where the input comes from, not for what it becomes,
 *  because the same input becomes a different record in each satellite. */
enum class RecordSource { VOICE, SCAN, NOTIFICATION }
