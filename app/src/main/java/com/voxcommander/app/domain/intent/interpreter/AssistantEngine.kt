package com.voxcommander.app.domain.intent.interpreter

import com.voxcommander.app.domain.intent.model.NluIntent

interface AssistantEngine {
    suspend fun processCommand(spokenText: String, modelFilterLang: String? = null): NluIntent?
}