package com.voxcommander.app.domain.intent.interpreter

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud-based Gemini interpreter using Google AI Generative API.
 * Requires a valid Gemini API key stored in settings.
 * Model: gemini-1.5-flash (fast, cost-effective for intent extraction).
 */
class GeminiCloudInterpreter(
    private val settingsRepo: SettingsRepository
) : AssistantEngine {

    private val TAG = Strings.Tags.GEMINI_NANO_INTERPRETER
    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? = withContext(Dispatchers.IO) {
        val apiKey = settingsRepo.getSettingsSnapshot().geminiApiKey
        if (apiKey.isNullOrBlank()) {
            Logger.log("Gemini API key not set — cannot use Gemini Cloud", TAG)
            return@withContext null
        }

        val model = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey
        )

        val systemPrompt = PromptProvider.getNluSystemPrompt(settingsRepo.getSettingsSnapshot(), modelFilterLang, settingsRepo)

        try {
            val response = model.generateContent(
                content {
                    text(systemPrompt)
                    text(PromptProvider.formatUserInput(spokenText))
                }
            )

            val responseText = response.text ?: return@withContext null
            Logger.log("Gemini Cloud response: $responseText", TAG)

            return@withContext NluIntentParser.parse(responseText)
        } catch (e: Exception) {
            Logger.log("Gemini Cloud inference failed: ${e.message}", TAG)
        }
        null
    }
}
