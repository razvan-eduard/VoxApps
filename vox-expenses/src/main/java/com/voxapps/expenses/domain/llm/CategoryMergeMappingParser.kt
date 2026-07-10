package com.voxapps.expenses.domain.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalizes the LLM's category-merge suggestion into a flat old-name -> canonical-name map,
 * regardless of which of the two shapes the model chose to answer in (mirrors vox-notes' parser
 * exactly):
 *  - flat:    {"duplicateName": "canonicalName", ...}
 *  - grouped: {"canonicalName": ["duplicateName", "otherDuplicate"], ...}
 */
object CategoryMergeMappingParser {
    fun parse(json: String): Map<String, String>? = try {
        val o = JSONObject(json)
        val result = mutableMapOf<String, String>()
        for (key in o.keys()) {
            when (val value = o.get(key)) {
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val duplicate = value.optString(i)
                        if (duplicate.isNotBlank()) result[duplicate] = key
                    }
                }
                else -> {
                    val canonical = value.toString()
                    if (canonical.isNotBlank()) result[key] = canonical
                }
            }
        }
        result
    } catch (e: Exception) {
        null
    }
}
