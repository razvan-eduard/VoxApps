package com.voxapps.datahygiene

import java.util.Locale

/**
 * The one shape a name a person types is written in.
 *
 * A list where "groceries", "Restaurant" and "UTILITIES" sit together reads as three kinds of thing
 * rather than three of the same kind, and the difference carries no information — it records which
 * of them was typed in a hurry. Casing them on the way in costs nothing and is the difference
 * between a list and a list somebody has to tidy.
 *
 * Every word, including short ones: no rule here lowercases joining words the way English titles do,
 * because which words those are is a fact about a language and these names are written in whichever
 * one the person speaks.
 */
object NameCasing {

    /**
     * `groceries` → `Groceries`, `public transport` → `Public Transport`.
     *
     * Whitespace runs collapse, so a stray double space does not survive as one. Casing is done
     * against [Locale.ROOT] rather than the device's: a name is stored once and read on every
     * device that syncs it, and the Turkish dotless *i* would otherwise make the same word come out
     * differently depending on who typed it.
     */
    fun titleCased(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1).lowercase(Locale.ROOT)
            }
    }

    private val WHITESPACE = Regex("""\s+""")
}
