package com.voxapps.expenses.data

/**
 * Deliberately EXACT (trim + lowercase only) — no diacritic stripping or fuzzy normalization like
 * [com.voxapps.textmatch.FuzzyNameMatcher] uses for category-name resolution. A wrong fuzzy match
 * here would silently misapply a learned category to a different real merchant, a much riskier
 * failure mode than under-matching. Known, accepted limitation: vendor text that differs by more
 * than case/whitespace (typos, abbreviations, store-number suffixes) is treated as a different
 * merchant.
 */
object MerchantVendorKey {
    fun normalize(vendor: String?): String? = vendor?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
