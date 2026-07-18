package com.voxapps.ipc

import org.json.JSONObject

/**
 * A satellite's contract for the collapsed voice-command extraction flow (see [VoxIpc.OP_GET_SCHEMA]/
 * [VoxIpc.ACTION_SCHEMA_CHANGED]). Commander fetches this once (proactively, from Integrations) and
 * caches it — it is *not* re-fetched per voice command.
 *
 * [needsExtractionPass] answers "does this satellite need a second LLM call after Commander's own
 * classification call" — required, never defaulted. A missing value on parse is treated as a malformed
 * contract ([fromJson] returns null), surfaced by the caller as a visible error rather than silently
 * assumed `false`; see the plan's reasoning for why a hidden default would just reintroduce the same
 * per-domain special-casing this contract exists to remove.
 *
 * [promptTemplate] is the satellite's own, fully-owned prompt text — hand-authored reasoning rules,
 * its KSP-generated field schema, and its current dynamic context (categories/currency/language or
 * equivalent) all pre-interpolated by the satellite at fetch/push time, with exactly one placeholder,
 * [INPUT_PLACEHOLDER], marking where Commander substitutes the per-command decomposition. Commander
 * never inspects or reconstructs this text — it only performs the one substitution and runs the LLM
 * call. Empty when [needsExtractionPass] is `false` (nothing to run a second pass with).
 *
 * [fieldSchemaVersion] mirrors `models.json`'s `schema_version` convention — bumped whenever the
 * satellite's KSP-generated field schema changes shape. Informational only (shown in Integrations for
 * debugging a stale cache); does not drive any automatic invalidation.
 *
 * [taskId] is the satellite-owned task identifier (e.g. Expenses' `LlmTasks.EXPENSE_PARSE`) Commander
 * must stamp on the [com.voxapps.ipc.VoxLlmResult] it delivers after running pass 2 — these strings
 * are owned entirely by each satellite's own `LlmResultReceiver` dispatch, never read or validated by
 * Commander, exactly like today's generic-LLM-hook `VoxLlmRequest.task`/`VoxLlmResult.task`. Empty
 * when [needsExtractionPass] is `false` (no delivery of this shape happens).
 */
data class VoxSatelliteSchema(
    val needsExtractionPass: Boolean,
    val promptTemplate: String = "",
    val fieldSchemaVersion: Int = 0,
    val taskId: String = ""
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("needsExtractionPass", needsExtractionPass)
        o.put("promptTemplate", promptTemplate)
        o.put("fieldSchemaVersion", fieldSchemaVersion)
        o.put("taskId", taskId)
        return o.toString()
    }

    /** Substitutes [INPUT_PLACEHOLDER] with the per-command decomposition/utterance text. */
    fun buildPrompt(input: String): String = promptTemplate.replace(INPUT_PLACEHOLDER, input)

    companion object {
        /** Marks where Commander substitutes the per-command input into [promptTemplate]. */
        const val INPUT_PLACEHOLDER = "{{INPUT}}"

        /**
         * Lenient parse; returns null if the payload is blank, not valid JSON, or missing the
         * required [needsExtractionPass] field — a missing declaration is a malformed contract, not
         * an implicit `false` (see class doc comment).
         */
        fun fromJson(json: String?): VoxSatelliteSchema? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                if (!o.has("needsExtractionPass")) return null
                VoxSatelliteSchema(
                    needsExtractionPass = o.getBoolean("needsExtractionPass"),
                    promptTemplate = o.optString("promptTemplate", ""),
                    fieldSchemaVersion = o.optInt("fieldSchemaVersion", 0),
                    taskId = o.optString("taskId", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
