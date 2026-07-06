package com.voxcommander.app.domain.intent.interpreter

import com.voxcommander.app.domain.engine.MemoryManagedComponent
import com.voxcommander.app.domain.intent.model.NluIntent

interface AssistantEngine : MemoryManagedComponent {
    suspend fun processCommand(spokenText: String, modelFilterLang: String? = null): NluIntent?
}