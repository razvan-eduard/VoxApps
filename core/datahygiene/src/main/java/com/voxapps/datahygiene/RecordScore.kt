package com.voxapps.datahygiene

/**
 * A capture-provenance tier for [recordScore] — distinct from [RecordSource], which routes a save
 * through the sanitize-or-confirm policy ([decideForSave]) rather than ranking data trustworthiness.
 * Each app defines its own tiers (e.g. Expenses' MANUAL/SCAN/NOTIFICATION/VOICE) via [trustTier]; the
 * absolute values only matter relative to each other within one app's own set, not across apps.
 */
interface RecordProvenance {
    val trustTier: Int
}

/**
 * Generic "which of two (or more) candidate records has the better data" score, for picking a merge
 * winner rather than always trusting whichever record arrived first — mirrors how contact-merge tools
 * (Google/Apple Contacts) and CRM dedup rank duplicate records: a human-edited record always outranks
 * everything else, then capture provenance (a manually-typed field is more trustworthy than an
 * OCR/voice-inferred one), then how many optional fields are actually filled in. [completenessFields]
 * should be exactly the record's own nullable/optional fields — the caller decides which fields count
 * (mirrors [RuleField]'s "app hand-writes its own field list" convention; this module has no
 * reflection-based field enumeration anywhere).
 */
fun recordScore(manuallyEdited: Boolean, provenance: RecordProvenance, completenessFields: List<Any?>): Int {
    val manualBonus = if (manuallyEdited) 10_000 else 0
    return manualBonus + provenance.trustTier + completenessFields.count { it != null }
}
