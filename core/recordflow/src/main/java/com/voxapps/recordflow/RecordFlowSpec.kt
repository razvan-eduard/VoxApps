package com.voxapps.recordflow

/**
 * Everything one satellite has to supply for one kind of input, and nothing more.
 *
 * The split is not arbitrary. What is shared between satellites is the *order* of the steps and the
 * rule that chooses between them — both of which live in [RecordFlow] and [RecordFlowPolicy]. What
 * cannot be shared is anything that touches a satellite's own vocabulary: what counts as proved,
 * what to ask a model, how to read its answer, and what a record even is. Those are the six members
 * below, and a satellite that implements them gets the flow rather than writing one.
 *
 * @param I the raw input — an utterance, a page's text, a notification's title and body.
 * @param T what the deterministic pass established, in the satellite's own terms.
 * @param P what a model's reply parsed to, when one was asked.
 */
interface RecordFlowSpec<I, T, P> {

    /** Which input this handles. One spec per source; a satellite that reads all three has three. */
    val source: RecordSource

    /** What this satellite can honestly offer for this input, and what it does by default. */
    val support: FlowSupport

    /**
     * The satellite's task identifier, stamped on the request so the reply routes back here. Owned
     * entirely by the satellite — nothing in core or in Commander reads it.
     */
    val taskId: String

    /** What the device can establish from the input alone, before anything is asked of anyone. */
    suspend fun read(input: I): DeterministicReading<T>

    /**
     * The prompt for what is still unproven, or null if there is nothing worth asking.
     *
     * Both arguments are needed to narrow a question, and neither is enough alone. [reading] says
     * what this page or sentence already yielded; [asks] says how much the chosen rung is willing to
     * ask about at all — whether the fine detail is even part of the question. Without the second,
     * every rung would send the same text and the scale would be decoration.
     */
    suspend fun prompt(reading: DeterministicReading<T>, asks: AskScope): String?

    /**
     * The same question, left open where the input goes — for transports that substitute it
     * elsewhere.
     *
     * Voice is the case: the utterance is heard by Commander, which already holds the satellite's
     * template and puts the words into it. So there is no reading to narrow against at the moment
     * the question is written, and what the satellite hands over is a form rather than a sentence.
     *
     * Null means this flow cannot be served that way, which is the honest answer for anything whose
     * question depends on what the device already established. A satellite that returns a template
     * must leave exactly one [com.voxapps.ipc.VoxSatelliteSchema.INPUT_PLACEHOLDER] in it.
     */
    suspend fun promptTemplate(asks: AskScope): String? = null

    /** A model's reply, in the satellite's terms, or null when it could not be understood. */
    suspend fun parse(reply: String): P?

    /**
     * Write the record.
     *
     * What the device proved is always written: it was established here, not proposed by anyone.
     * A model's answer is written only for the weights [applies] accepts, and whatever it rejects is
     * offered instead — which is the satellite's to do, because only it knows which of its fields
     * are head and which are body, and only it has somewhere to put a proposal.
     *
     * [parsed] is null when no model was asked. The offline path is not a different method, only
     * this argument being absent.
     *
     * [reading] is null when a reply arrived and the reading behind it could not be recovered. That
     * is the ordinary case rather than a failure: the two halves of this flow are separated by a
     * process boundary, and the bus carries the answer back, not the question. A satellite that
     * needs its reading at that point persists it against the request and hands it back here; one
     * that does not — because the reply already contains everything the record needs — simply reads
     * null as "the answer is all there is".
     *
     * Per-capture facts that are neither reading nor reply — a staged photograph's name, the record
     * being amended — belong on the spec itself, which is built once per capture and is the only
     * thing present in both halves.
     *
     * @return the new record's id, or null if nothing was written after all.
     */
    suspend fun commit(
        reading: DeterministicReading<T>?,
        parsed: P?,
        applies: (FieldWeight) -> Boolean = { true }
    ): Long?

    /** Keep it where a person decides. Same two arguments as [commit], for the same reason. */
    suspend fun queueForReview(reading: DeterministicReading<T>?, parsed: P?)

    /**
     * Whether a fully proved reading may be written without review. Defaults to yes; a channel that
     * nobody deliberately triggered — a captured notification — can answer no permanently.
     */
    suspend fun autoAcceptWhenProven(): Boolean = true

}
