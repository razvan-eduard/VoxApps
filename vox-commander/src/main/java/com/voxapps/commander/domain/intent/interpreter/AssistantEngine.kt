package com.voxapps.commander.domain.intent.interpreter

import com.voxapps.commander.domain.engine.MemoryManagedComponent
import com.voxapps.commander.domain.intent.model.NluIntent

interface AssistantEngine : MemoryManagedComponent {
    suspend fun processCommand(spokenText: String, modelFilterLang: String? = null): NluIntent?
}