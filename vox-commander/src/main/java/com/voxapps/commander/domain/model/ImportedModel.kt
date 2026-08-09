package com.voxapps.commander.domain.model

import java.io.File

/**
 * A model the user imported, listed beside the ones the schema declares.
 *
 * An import used to be invisible here and unconditional everywhere else: it was not in any model
 * list, so the dropdown could not show it and its delete could not reach it, while the loader
 * preferred it over whatever the dropdown *did* show. Picking a downloaded model marked that model
 * as chosen and kept running the imported file.
 *
 * As a row it carries the same weight as a downloaded one — selected the same way, removed by the
 * same trash icon, and loaded because it is selected rather than because it exists.
 *
 * [isBuiltIn] is false deliberately. The grouped picker hides both icons for a built-in model, and
 * this one wants no download arrow (there is nothing to fetch) but does want a delete.
 */
data class ImportedModel(
    override val id: String,
    override val label: String,
    override val sizeDescription: String,
    override val engineType: String,
    override val langCode: String? = null,
    override val isBuiltIn: Boolean = false,
    /** Nothing to fetch: the file is already on disk, put there by the import. */
    override val url: String = ""
) : AppModel {

    companion object {

        /** Built from the file itself, so the label and size describe what is actually there. */
        fun of(file: File, engineKey: String, langCode: String?): ImportedModel = ImportedModel(
            id = ImportedModelId.of(engineKey, langCode),
            label = file.name,
            sizeDescription = describeSize(file),
            engineType = engineKey,
            langCode = langCode
        )

        /** A directory-packaged model is the sum of its parts; a single-file one is its own size. */
        private fun describeSize(file: File): String {
            val bytes = if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                file.length()
            }
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1024 -> String.format("%.1f GB", mb / 1024)
                mb >= 1 -> "${mb.toInt()} MB"
                // Rounding a small model to "0 MB" reads as an empty import.
                else -> "${kb.toInt()} KB"
            }
        }
    }
}

/**
 * The id an imported model is stored and selected under.
 *
 * It has to be derivable in both directions: a stored selection is recognised as an import without
 * consulting anything, and the engine it belongs to is read back out of it. The shape mirrors the
 * key `setCustomModelPath` already uses, so the two never disagree about which import is which.
 */
object ImportedModelId {

    private const val PREFIX = "custom:"

    fun of(engineKey: String, langCode: String? = null): String =
        if (langCode.isNullOrBlank()) "$PREFIX$engineKey" else "$PREFIX$engineKey:$langCode"

    fun isImported(modelId: String?): Boolean = modelId?.startsWith(PREFIX) == true

    /** The engine an imported id belongs to, or null when the id is not one. */
    fun engineOf(modelId: String?): String? =
        modelId?.takeIf { isImported(it) }?.removePrefix(PREFIX)?.substringBefore(':')

    /** The language an imported id was stored for, for the engines that keep one per language. */
    fun langOf(modelId: String?): String? =
        modelId?.takeIf { isImported(it) }?.removePrefix(PREFIX)?.substringAfter(':', "")
            ?.takeIf { it.isNotBlank() }
}
