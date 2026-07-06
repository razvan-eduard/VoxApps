package com.voxcommander.app.utils

object TextUtils {

    private val SENTENCE_SPLIT_REGEX = "(?<=[.!?])\\s+".toRegex()

    fun splitSentences(text: String): List<String> {
        val sentences = text.split(SENTENCE_SPLIT_REGEX).filter { it.isNotBlank() }
        return if (sentences.isEmpty()) listOf(text) else sentences
    }
}
