package com.voxapps.recordflow

/**
 * What the device established on its own, before anything was asked of a model.
 *
 * [usable] is the floor: whether there is enough here to be worth keeping at all — an amount for an
 * expense, a body for a note. Below it there is no record to write and nothing to review, and the
 * capture ends silently, which is what makes a promotional message harmless.
 *
 * [complete] is the ceiling: whether everything the record needs was *proved*, not merely present.
 * A field that had to be assumed does not count, because the whole point of the distinction is that
 * an assumption is what a person or a model is supposed to resolve.
 */
data class DeterministicReading<T>(
    val fields: T,
    val usable: Boolean,
    val complete: Boolean
) {
    init {
        require(!complete || usable) { "a reading cannot be complete without being usable" }
    }
}

/** What the flow does next. */
enum class Decision {
    /** Write the record now. */
    COMMIT,

    /** Keep it where a person decides — the answer whenever something is unproven and no model
     *  will be asked. */
    QUEUE_FOR_REVIEW,

    /** Compose the satellite's prompt and send it; the reply arrives later. */
    ASK_MODEL,

    /** Nothing worth keeping. No record, no queue entry, no request. */
    DISCARD
}

/**
 * The one switch, as a function of what is known — the same for every satellite and every input.
 *
 * This existed three times before it existed once, in three shapes that disagreed: the scan created
 * a record whenever it had a total, the notification channel queued or created depending on a
 * setting, and voice had no offline branch at all. The disagreements were not decisions anybody
 * made; they were what each path happened to grow. Written down in one place, the rule turns out to
 * be short, and the differences between satellites turn out to live entirely in what they can prove
 * — which is the input to this function, not part of it.
 */
object RecordFlowPolicy {

    /**
     * @param autoAcceptWhenProven whether a fully proved reading may be written without review.
     *  Off, everything still passes a person — which some channels want permanently: a notification
     *  was never a deliberate action by anyone.
     */
    fun decide(
        level: LlmLevel,
        reading: DeterministicReading<*>,
        autoAcceptWhenProven: Boolean = true
    ): Decision = when (level.asks) {
        // Nothing may be sent, so what was not proved is a person's to answer. A reading below the
        // floor leaves nothing to review either, and the capture ends without a trace.
        AskScope.NOTHING -> when {
            !reading.usable -> Decision.DISCARD
            reading.complete && autoAcceptWhenProven -> Decision.COMMIT
            else -> Decision.QUEUE_FOR_REVIEW
        }

        // Only what is missing is asked about — so a reading that proved everything asks nothing at
        // all. Not an optimisation: there was no question. Where the deterministic pass established
        // nothing, every field is unproven and the ask is total; that this coincides with the wider
        // scopes is not a gap in the distinction but the honest consequence of it.
        AskScope.MISSING_HEAD -> when {
            !reading.complete -> Decision.ASK_MODEL
            autoAcceptWhenProven -> Decision.COMMIT
            else -> Decision.QUEUE_FOR_REVIEW
        }

        // The wider scopes ask regardless of what was proved; they part company only when the answer
        // comes back — see [RecordFlow.deliver] and [LlmLevel.applies].
        AskScope.ALL_HEAD, AskScope.EVERYTHING -> Decision.ASK_MODEL
    }
}
