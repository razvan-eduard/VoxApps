package com.voxapps.recordflow

import com.voxapps.logging.Logger

private const val TAG = "RecordFlow"

/**
 * The shared shape of turning an input into a record: read what can be proved, decide who answers
 * the rest, and either write it, keep it for a person, or ask.
 *
 * It comes in two halves because the flow genuinely has two halves. Asking a model crosses the
 * process boundary and comes back later through a broadcast, so no single call can carry a capture
 * from beginning to record. [dispatch] ends either with a record or with a request in flight;
 * [deliver] resumes when the answer arrives. Papering that over with one suspending call would
 * hide the only part of this flow that can fail silently — the answer that never comes — which is
 * why the durable queue exists in the first place.
 */
object RecordFlow {

    /** What a dispatch ended in, for the caller's own accounting and for the log. */
    sealed interface Outcome {
        /** Written, with the id it was written under. */
        data class Committed(val recordId: Long) : Outcome

        /** Waiting for a person. */
        data object Queued : Outcome

        /** A request is in flight; the reply will arrive at [deliver]. */
        data object Asked : Outcome

        /** Nothing worth keeping, and nothing sent. */
        data object Discarded : Outcome
    }

    /**
     * Read the input, decide, and act.
     *
     * [send] is how a request reaches the model — supplied by the caller rather than reached for
     * here, because delivery is durable, retryable and owned by the app that has the queue. It is
     * called with the satellite's own prompt and task id, and with nothing else: this module never
     * composes a prompt and never inspects one.
     */
    suspend fun <I, T, P> dispatch(
        spec: RecordFlowSpec<I, T, P>,
        input: I,
        level: LlmLevel,
        send: suspend (taskId: String, prompt: String) -> Unit
    ): Outcome {
        val effective = effectiveLevel(spec, level)
        val reading = spec.read(input)
        val decision = RecordFlowPolicy.decide(effective, reading, spec.autoAcceptWhenProven())
        Logger.d(TAG, "${spec.source} at $effective: usable=${reading.usable} complete=${reading.complete} -> $decision")

        return when (decision) {
            Decision.DISCARD -> Outcome.Discarded

            Decision.COMMIT -> spec.commit(reading, null)
                ?.let { Outcome.Committed(it) }
                ?: Outcome.Discarded

            Decision.QUEUE_FOR_REVIEW -> {
                spec.queueForReview(reading, null)
                Outcome.Queued
            }

            Decision.ASK_MODEL -> {
                val prompt = spec.prompt(reading, effective.asks)
                if (prompt.isNullOrBlank()) {
                    // Nothing to ask after all. Falling back to the offline answer rather than
                    // sending an empty request keeps a satellite's "I have no question" from
                    // becoming a round trip that returns nothing.
                    Logger.d(TAG, "${spec.source} had nothing to ask — answering from the reading alone")
                    return dispatch(spec, input, LlmLevel.NONE, send)
                }
                send(spec.taskId, prompt)
                Outcome.Asked
            }
        }
    }

    /**
     * The other half: a model answered, so finish the record.
     *
     * A reply that cannot be parsed does not discard the capture — whatever the device proved is
     * still true, so it goes to review. Losing a capture because an answer came back malformed
     * would be the worst of both, having sent the text and kept nothing.
     */
    suspend fun <I, T, P> deliver(
        spec: RecordFlowSpec<I, T, P>,
        reading: DeterministicReading<T>?,
        level: LlmLevel,
        reply: String
    ): Outcome {
        val parsed = spec.parse(reply)
        if (parsed == null) {
            Logger.w(TAG, "${spec.source}: the reply could not be read — keeping the reading for review")
            spec.queueForReview(reading, null)
            return Outcome.Queued
        }
        // The record is written from what the device proved either way; the level only decides how
        // much of the answer joins it now and how much waits to be accepted. Even at a level that
        // writes none of it, the record has to exist — a proposal has to be attached to something.
        val applies = effectiveLevel(spec, level)::applies
        return spec.commit(reading, parsed, applies)
            ?.let { Outcome.Committed(it) }
            ?: Outcome.Discarded
    }

    /**
     * The level actually applied.
     *
     * A satellite that cannot honour the level it was handed says so in its [FlowSupport], and the
     * flow falls back to that flow's default rather than pretending. It is logged loudly: the only
     * way to arrive here is a stored setting for a level that has since been withdrawn, which is a
     * mismatch someone should see rather than a state to accommodate quietly.
     */
    private fun effectiveLevel(spec: RecordFlowSpec<*, *, *>, requested: LlmLevel): LlmLevel =
        if (requested in spec.support.supported) {
            requested
        } else {
            Logger.w(TAG, "${spec.source} cannot honour $requested — falling back to ${spec.support.default}")
            spec.support.default
        }
}
