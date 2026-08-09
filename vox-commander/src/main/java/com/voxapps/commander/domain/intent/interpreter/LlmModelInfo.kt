package com.voxapps.commander.domain.intent.interpreter

import com.voxapps.commander.domain.model.AppModel

/**
 * Dynamic LLM Model Info for NLU tasks (Qwen, Gemma, etc).
 * Maps from RemoteModelRegistry dynamic items.
 */
data class LlmModelInfo(
    override val id: String,
    override val label: String,
    override val sizeDescription: String,
    override val url: String,
    val engineTypeTag: String // e.g. "LITERTLM"
) : AppModel {
    override val engineType: String get() = "nlu_llm"
    override val langCode: String? get() = null
    override val isBuiltIn: Boolean get() = false
}
