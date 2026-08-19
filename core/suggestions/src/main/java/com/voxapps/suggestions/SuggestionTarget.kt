package com.voxapps.suggestions

import com.voxapps.recordflow.FieldWeight

/**
 * A field a satellite is willing to have suggested on its own record page.
 *
 * Declared rather than inferred, because "what may be proposed" is a product decision and not a
 * property of the data: a record has fields nobody should be offered a machine's opinion about, and
 * which ones those are differs per app. A field absent from this list simply never carries a
 * suggestion, whatever a reply contained.
 *
 * [label] arrives already translated — core has no LanguageManager and never will, for the same
 * reason `:core:design` takes its strings from the caller.
 */
data class SuggestableField(
    val key: String,
    val label: String,
    /**
     * Whether this field is coarse enough that a machine's answer for it may be written unread.
     *
     * The satellite decides, because only it knows which of its fields are checkable at a glance. A
     * whole list of line items is one field of weight [FieldWeight.BODY]; a vendor name is
     * [FieldWeight.HEAD]. Defaults to BODY: caution is the safer thing to have to override.
     */
    val weight: FieldWeight = FieldWeight.BODY
)

/**
 * What one satellite can do with suggestions for one kind of record.
 *
 * The storage, the lifecycle and the affordance are core's; the meaning is the satellite's. This is
 * the whole of the second half: which fields exist, and what accepting one does to a record only the
 * satellite knows the shape of.
 */
interface SuggestionTarget {

    /** Every field this app allows a suggestion for, in the order its record page shows them. */
    val suggestableFields: List<SuggestableField>

    /**
     * The record's current value for [fieldKey], encoded the same way a suggestion is, so the two
     * can be compared without core knowing either one's type. A field whose current value equals
     * the proposal is not worth showing, and this is how that is decided.
     */
    suspend fun currentValue(recordId: Long, fieldKey: String): String?

    /**
     * Accept [value] for [fieldKey] on [recordId].
     *
     * The satellite decodes and writes it; core only removes the suggestion afterwards. A field the
     * satellite declines to apply — because the value no longer parses, or the record moved on —
     * returns false, and the suggestion is left alone rather than discarded, so nothing is lost by
     * an accept that could not be carried out.
     */
    suspend fun applyValue(recordId: Long, fieldKey: String, value: String?): Boolean

    /**
     * Dispose of whatever produced these suggestions, if anything needs disposing. Called after the
     * last suggestion carrying [sourceTag] is dismissed. Does nothing by default, since most sources
     * leave nothing behind.
     */
    suspend fun discardSource(recordId: Long, sourceTag: String) {}
}
