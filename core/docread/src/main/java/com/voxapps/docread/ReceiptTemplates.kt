package com.voxapps.docread

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.voxapps.services.RemoteSchema

/**
 * The library of document shapes a scan is read with, fed by signed schema rather than compiled in.
 *
 * A receipt format is data, not logic. The arithmetic that decides whether a reading is correct is
 * the same for every document ever printed — rows must reconcile with a figure the document itself
 * shows — while the shapes those rows can take are endless and country-specific. Keeping the shapes
 * in a signed file means a format nobody anticipated becomes a repository edit, and every install
 * picks it up without an app release.
 *
 * The three regions are **independent lists**, and any header may combine with any items pattern and
 * any footer pattern. That matters because the regions vary independently in the wild: two documents
 * can print their totals identically and their rows nothing alike. A fixed set of whole-document
 * templates would need one entry per pairing; three small lists cover the same ground as their
 * product.
 *
 * Only [items] and [footer] are ever judged — between them they produce numbers that either
 * reconcile or do not. [header] carries no arithmetic to check, so it is read best-effort and can
 * never veto a combination the figures already proved.
 */
data class ReceiptTemplateSchema(
    @SerializedName("amount") val amount: String = "",
    @SerializedName("columns") val columns: ColumnTemplateEntry? = null,
    @SerializedName("captions") val captions: CaptionTemplateEntry? = null,
    @SerializedName("header") val header: List<HeaderTemplateEntry> = emptyList(),
    @SerializedName("items") val items: List<ItemTemplateEntry> = emptyList(),
    @SerializedName("footer") val footer: List<FooterTemplateEntry> = emptyList()
)

/**
 * How a table's column headings are recognised: as **terms**, per language, rather than as patterns.
 *
 * Word lists are what this actually is. Which words head a quantity column is knowledge that grows
 * one word at a time, in four languages, from documents nobody has seen yet — and a contributor who
 * knows that "Stuckpreis" heads a unit price should not have to write a regular expression to say
 * so. Matching is left to the classifier the rest of the app already uses, which reads "U.M." and
 * "um" as the same term without either being spelled twice.
 *
 * The relation stays a pattern because it is not a word: it is the arithmetic a numbered form prints
 * about itself, and it means the same in every language.
 */
data class ColumnTemplateEntry(
    @SerializedName("describes") val describes: String = "",
    /** The relation a numbered form prints between its own columns, e.g. `5 (3x4)`. */
    @SerializedName("relation") val relation: String? = null,
    /** Language to role to the words that head that column. */
    @SerializedName("vocabularies") val vocabularies: Map<String, Map<String, List<String>>> = emptyMap()
)

/**
 * The words that introduce a party or a date in a letterhead, per language.
 *
 * The buyer's words are here for the same reason the seller's are, and matter more: an invoice names
 * both parties, in the same shape, often within a line of each other, so a reading that only knows
 * what a seller is called will take whichever came first. Naming the buyer is what makes it possible
 * to rule that line out rather than hope.
 */
data class CaptionTemplateEntry(
    @SerializedName("describes") val describes: String = "",
    /** Language to role (`seller`, `buyer`, `issue_date`, `due_date`) to the words that introduce it. */
    @SerializedName("vocabularies") val vocabularies: Map<String, Map<String, List<String>>> = emptyMap()
)

/** A caption vocabulary compiled for the shared classifier. */
data class CompiledCaptions(
    val language: String,
    val roles: Map<String, com.voxapps.textmatch.extract.VocabularyClassifier.Vocabulary>
)

data class HeaderTemplateEntry(
    @SerializedName("id") val id: String = "",
    @SerializedName("describes") val describes: String = "",
    /** Field name to a pattern with a `value` group — vendor, invoiceNumber, date, taxId. */
    @SerializedName("fields") val fields: Map<String, String> = emptyMap()
)

data class ItemTemplateEntry(
    @SerializedName("id") val id: String = "",
    @SerializedName("describes") val describes: String = "",
    @SerializedName("row") val row: String = "",
    @SerializedName("continuation") val continuation: String? = null
)

data class FooterTemplateEntry(
    @SerializedName("id") val id: String = "",
    @SerializedName("describes") val describes: String = "",
    /** How captions relate to amounts on this document; see [FooterMode]. */
    @SerializedName("mode") val mode: String = "",
    /** Role name to the caption pattern that names it. */
    @SerializedName("roles") val roles: Map<String, String> = emptyMap()
)

/**
 * How a footer prints its captions relative to its figures — the part that cannot be expressed as a
 * pattern, because it is about layout rather than wording.
 */
enum class FooterMode {
    /** Caption and amount on one line: `Total Factura   22.21`. */
    INLINE,

    /**
     * Captions in one column and amounts in another, so a caption's own line holds no figure. Common
     * on pre-printed forms, and the case that silently mis-assigns totals when read as [INLINE]:
     * every amount lands against its neighbour's caption.
     */
    STACKED,

    /** No caption survived recognition; roles are settled by arithmetic over the figures alone. */
    ARITHMETIC;

    companion object {
        fun of(raw: String): FooterMode? = when (raw.lowercase()) {
            "inline" -> INLINE
            "stacked" -> STACKED
            "arithmetic" -> ARITHMETIC
            else -> null
        }
    }
}

/** A template with its patterns compiled once, since the search runs them over every line. */
data class CompiledFooter(val id: String, val mode: FooterMode, val roles: Map<String, Regex>)

data class CompiledHeader(val id: String, val fields: Map<String, Regex>)

data class CompiledColumns(
    val id: String,
    /** Role to the words that head it, ready for the shared classifier. */
    val headings: Map<String, com.voxapps.textmatch.extract.VocabularyClassifier.Vocabulary>,
    val relation: Regex?
)

object ReceiptTemplates {

    private const val TAG = "ReceiptTemplates"

    /** The roles a footer may name. Anything else in a schema is ignored rather than refused, so a
     *  newer repository can add one without older installs rejecting the whole file. */
    const val ROLE_GRAND_TOTAL = "grandTotal"
    const val ROLE_INVOICE_TOTAL = "invoiceTotal"
    const val ROLE_PREVIOUS_BALANCE = "previousBalance"
    const val ROLE_NET = "net"
    const val ROLE_VAT = "vat"

    private val schema = RemoteSchema(
        fileName = "receipt_templates.json",
        type = ReceiptTemplateSchema::class.java,
        usable = { isUsable(it) },
        tag = TAG
    )

    fun init(context: Context) = schema.init(context)

    private fun value(context: Context): ReceiptTemplateSchema? {
        if (!schema.isLoaded) init(context)
        return schema.value
    }

    /**
     * The row patterns, in file order.
     *
     * Order is the whole priority mechanism: the first combination that reconciles wins, so a
     * pattern demanding a quantity, a unit price and a value must be offered before one that matches
     * any line ending in a number. Falls back to the compiled-in set, which is what every install
     * ships with and what runs before the first fetch succeeds.
     */
    fun items(context: Context): List<LineItemBattery.Template> =
        value(context)?.let { compiled(it).items }?.takeIf { it.isNotEmpty() } ?: LineItemBattery.BUILT_IN

    fun footers(context: Context): List<CompiledFooter> =
        value(context)?.let { compiled(it).footers } ?: emptyList()

    fun headers(context: Context): List<CompiledHeader> =
        value(context)?.let { compiled(it).headers } ?: emptyList()

    /** Every usable template in a parsed file. Free of Android so the shipped library itself can be
     *  put through a real document in a unit test, which is the only way its patterns are proven. */
    fun compiled(value: ReceiptTemplateSchema) = Compiled(
        columns = value.columns?.compileAll().orEmpty(),
        captions = value.captions?.compileAll().orEmpty(),
        headers = value.header.mapNotNull { it.compile() },
        items = value.items.mapNotNull { it.compile() },
        footers = value.footer.mapNotNull { it.compile() }
    )

    data class Compiled(
        val columns: List<CompiledColumns>,
        val captions: List<CompiledCaptions>,
        val headers: List<CompiledHeader>,
        val items: List<LineItemBattery.Template>,
        val footers: List<CompiledFooter>
    )

    private fun ItemTemplateEntry.compile(): LineItemBattery.Template? {
        if (id.isBlank() || row.isBlank()) return null
        val rowRegex = row.toRegexOrNull() ?: return null
        // A row that names neither a value nor a quantity-and-price pair cannot produce an amount,
        // so it could never be judged; refusing it here keeps the search honest.
        if (!rowRegex.namesValue()) return null
        return LineItemBattery.Template(
            id = id,
            row = rowRegex,
            continuation = continuation?.toRegexOrNull()
        )
    }

    private fun FooterTemplateEntry.compile(): CompiledFooter? {
        if (id.isBlank()) return null
        val parsed = FooterMode.of(mode) ?: return null
        val compiled = roles.mapNotNull { (role, pattern) ->
            pattern.toRegexOrNull()?.let { role to it }
        }.toMap()
        // Arithmetic needs no captions; every other mode is defined by having some.
        if (parsed != FooterMode.ARITHMETIC && compiled.isEmpty()) return null
        return CompiledFooter(id, parsed, compiled)
    }

    fun columns(context: Context): List<CompiledColumns> =
        value(context)?.let { compiled(it).columns } ?: emptyList()

    /**
     * One entry per language. A document is not asked which language it is in — every language's
     * words are offered, and the arithmetic settles which reading was right, exactly as it settles
     * everything else here. An invoice may well head one column in English and another in the local
     * language, which is a case no single-language reading covers.
     */
    fun captions(context: Context): List<CompiledCaptions> =
        value(context)?.let { compiled(it).captions } ?: emptyList()

    private fun CaptionTemplateEntry.compileAll(): List<CompiledCaptions> =
        vocabularies.mapNotNull { (language, roles) ->
            val compiled = roles.filterValues { it.isNotEmpty() }.mapValues { (role, terms) ->
                com.voxapps.textmatch.extract.VocabularyClassifier.Vocabulary(role, terms)
            }
            if (compiled.isEmpty()) null else CompiledCaptions(language, compiled)
        }

    private fun ColumnTemplateEntry.compileAll(): List<CompiledColumns> =
        vocabularies.mapNotNull { (language, roles) ->
            val headings = roles
                .filterValues { it.isNotEmpty() }
                .mapValues { (role, terms) ->
                    com.voxapps.textmatch.extract.VocabularyClassifier.Vocabulary(role, terms)
                }
            if (headings.isEmpty()) null
            else CompiledColumns(language, headings, relation?.toRegexOrNull())
        }

    private fun HeaderTemplateEntry.compile(): CompiledHeader? {
        if (id.isBlank()) return null
        val compiled = fields.mapNotNull { (field, pattern) ->
            pattern.toRegexOrNull()?.let { field to it }
        }.toMap()
        return if (compiled.isEmpty()) null else CompiledHeader(id, compiled)
    }

    /**
     * A file is usable when it can actually read something.
     *
     * Deliberately per-entry rather than all-or-nothing: one malformed pattern in a growing library
     * discards that pattern, not the library. What is refused is a file with no usable row pattern
     * at all, since serving it would silently disable line items everywhere.
     */
    fun isUsable(value: ReceiptTemplateSchema): Boolean =
        value.items.any { it.compile() != null }

    /** Compilation failure is a property of the data, never a crash: a bad pattern is skipped. */
    private fun String.toRegexOrNull(): Regex? =
        if (length > MAX_PATTERN_LENGTH) null else runCatching { Regex(this) }.getOrNull()

    private fun Regex.namesValue(): Boolean {
        val p = pattern
        return p.contains("(?<value>") || (p.contains("(?<qty>") && p.contains("(?<unit>"))
    }

    /** A pattern longer than this is not a document shape; the cap bounds the cost of a bad file. */
    private const val MAX_PATTERN_LENGTH = 600
}
