package com.voxapps.notes.domain.llm

/**
 * Languages Vox Notes ships both a translation file for (see `assets/translations/`) and offers in
 * the language picker — kept in sync with vox-commander's own supported set.
 */
object SupportedLanguages {
    val ALL = listOf("en", "ro", "de", "fr")
}
