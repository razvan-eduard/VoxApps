package com.voxapps.expenses.domain.archive

import java.util.concurrent.TimeUnit

/** When the archive should let go of what it is holding. */
object ArchiveRetention {

    /**
     * The moment before which an archived record has outstayed its welcome, or null when nothing
     * has: [retentionDays] of zero or less is "keep everything", and that is not a cutoff of now —
     * it is the absence of one.
     *
     * Separated from the deleting so the rule can be stated once and read as a sentence. The whole
     * feature turns on this returning null rather than [nowMillis] when nothing is set, and a null
     * is very much easier to be sure of than a branch inside a worker.
     */
    fun cutoff(retentionDays: Int, nowMillis: Long): Long? =
        if (retentionDays <= 0) null else nowMillis - TimeUnit.DAYS.toMillis(retentionDays.toLong())
}
