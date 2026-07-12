package com.voxapps.commander.domain.integration

import com.voxapps.ipc.VoxAppInfo

/**
 * Pure decision for "which satellite gets this command" when several apps claim the same domain.
 * No Android dependencies → unit-testable. The hierarchy (highest priority first):
 *
 *  1. Explicit — an app the user named in the utterance ("save in OpenNotes"), if it's a candidate.
 *  2. Star     — the user's default for the domain (Settings → Default Apps), if it's a candidate.
 *  3. First-party — a satellite signed with Commander's own key (Vox Notes beats OpenNotes silently).
 *  4. Single   — exactly one candidate → route to it.
 *  5. Fallback — 2+ third-party and no star: first discovered (DEFERRED: voice disambiguation later).
 */
object SatelliteRouting {

    data class Decision(val packageName: String?, val ambiguous: Boolean)

    fun pick(
        candidates: List<VoxAppInfo>,
        starredPkg: String? = null,
        explicitPkg: String? = null
    ): Decision {
        if (candidates.isEmpty()) return Decision(null, ambiguous = false)

        fun candidate(pkg: String?) = pkg?.let { p -> candidates.firstOrNull { it.packageName == p } }

        candidate(explicitPkg)?.let { return Decision(it.packageName, ambiguous = false) }
        candidate(starredPkg)?.let { return Decision(it.packageName, ambiguous = false) }

        candidates.firstOrNull { it.isFirstParty }?.let { return Decision(it.packageName, ambiguous = false) }

        if (candidates.size == 1) return Decision(candidates.first().packageName, ambiguous = false)

        // 2+ third-party, no star → deferred disambiguation; route to first, flag as ambiguous.
        return Decision(candidates.first().packageName, ambiguous = true)
    }
}
