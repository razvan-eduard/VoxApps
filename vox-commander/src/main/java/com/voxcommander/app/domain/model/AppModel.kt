package com.voxcommander.app.domain.model

/**
 * Common interface for all AI models (Whisper, Vosk, NLU) 
 * to enable unified UI management (dialogs, progress, etc).
 */
interface AppModel {
    val id: String
    val label: String
    val sizeDescription: String
    val url: String
    val engineType: String // "stt_whisper", etc.
    val langCode: String?
    val isBuiltIn: Boolean // true for bundled/asset models (no download/delete needed)
}
