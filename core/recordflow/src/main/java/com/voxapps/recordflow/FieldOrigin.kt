package com.voxapps.recordflow

/**
 * Where a field's value came from.
 *
 * A record made from a capture holds three very different kinds of value side by side: a figure the
 * document proved, a name a list this device keeps recognised, and an answer a model gave. They read
 * identically once written, which is why a wrong one reads as "the app is bad" rather than as "the
 * model guessed that one" — and why the second reaction is the one that lets somebody keep using it.
 *
 * The flow already knows this at the moment it writes. Keeping it costs a string per record and
 * turns a machine that edits your data into one that shows its work.
 */
enum class FieldOrigin(val stored: String) {
    /** The source itself said so, and arithmetic or a format agreed: a printed total that its rows
     *  sum to, an amount marked by a currency, an IBAN whose checksum holds. */
    PROVED("proved"),

    /** A list this device keeps recognised it — a bank, a shop, a legal form. Deterministic, but
     *  true only because somebody curated the list. */
    MATCHED("matched"),

    /** A model answered it. Everything else about this record may be certain; this is the part that
     *  was read rather than worked out. */
    ANSWERED("answered"),

    /** A person wrote it, which outranks everything above and is never overwritten by a later pass. */
    TYPED("typed");

    companion object {
        fun of(stored: String?): FieldOrigin? = entries.firstOrNull { it.stored == stored }
    }
}
