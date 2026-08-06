package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud-based Gemini interpreter using Google AI Generative API.
 * Requires a valid Gemini API key stored in settings.
 * Model: gemini-1.5-flash (fast, cost-effective for intent extraction).
 */
class GeminiCloudInterpreter(
    private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val fastMapDao: FastMapDao
) : AssistantEngine {

    private val TAG = Strings.Tags.GEMINI_NANO_INTERPRETER
    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? = withContext(Dispatchers.IO) {
        val snapshot = settingsRepo.getSettingsSnapshot()
        val apiKey = snapshot.geminiApiKey
        if (apiKey.isNullOrBlank()) {
            Logger.log("Gemini API key not set — cannot use Gemini Cloud", TAG)
            return@withContext null
        }

        val model = GenerativeModel(
            modelName = Strings.Models.GEMINI_1_5_FLASH,
            apiKey = apiKey
        )

        val activeRules = fastMapDao.getAllRulesOnce().filter { it.isActive }
        val systemPrompt = PromptProvider.getNluSystemPrompt(spokenText, snapshot, modelFilterLang, settingsRepo, activeRules)

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

    override suspend fun rawPrompt(promptText: String, imageUri: String?): String? = withContext(Dispatchers.IO) {
        val apiKey = settingsRepo.getSettingsSnapshot().geminiApiKey
        if (apiKey.isNullOrBlank()) {
            Logger.log("Gemini API key not set — cannot use Gemini Cloud (rawPrompt)", TAG)
            return@withContext null
        }
        val model = GenerativeModel(modelName = Strings.Models.GEMINI_1_5_FLASH, apiKey = apiKey)
        val bitmap = imageUri?.let { ImageAttachmentUtil.readBitmap(appContext, it) }
        try {
            model.generateContent(content {
                text(promptText)
                bitmap?.let { image(it) }
            }).text
        } catch (e: Exception) {
            Logger.log("Gemini Cloud rawPrompt failed: ${e.message}", TAG)
            null
        }
    }
}
