package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.CloudDeadline
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
        // Read straight from the credential store, not from the snapshot: the snapshot is a cached
        // DataStore emission and the keys live in EncryptedSharedPreferences, so a key entered a
        // moment ago may not be in it yet. OpenAI has always read it this way.
        val apiKey = settingsRepo.getCredentialsSnapshot().forEngine(Strings.AiProcessors.GEMINI_CLOUD)
        if (apiKey.isNullOrBlank()) {
            Logger.log("Gemini API key not set — cannot use Gemini Cloud", TAG)
            return@withContext null
        }

        val model = boundedModel(apiKey)

        val activeRules = fastMapDao.getAllRulesOnce().filter { it.isActive }
        val systemPrompt = PromptProvider.getNluSystemPrompt(spokenText, snapshot, modelFilterLang, settingsRepo, activeRules, Strings.AiProcessors.GEMINI_CLOUD)

        try {
            val response = CloudDeadline.run(Strings.AiProcessors.GEMINI_CLOUD, settingsRepo) {
                model.generateContent(
                    content {
                        text(systemPrompt)
                        text(PromptProvider.formatUserInput(spokenText))
                    }
                )
            } ?: return@withContext null

            val responseText = response.text ?: return@withContext null
            Logger.log("Gemini Cloud response: $responseText", TAG)

            return@withContext NluIntentParser.parse(responseText)
        } catch (e: Exception) {
            Logger.log("Gemini Cloud inference failed: ${e.message}", TAG)
        }
        null
    }

    /**
     * The SDK's own request budget, set from the same value the caller's deadline uses.
     *
     * Both are needed. [CloudDeadline.run] is what lets the cascade move on to the user's fallback;
     * [RequestOptions] is what stops the SDK's HTTP call outliving that decision — its default is
     * `Long.MAX_VALUE`, so until now a Gemini Cloud request had no bound of any kind.
     */
    private fun boundedModel(apiKey: String) = GenerativeModel(
        modelName = Strings.Models.GEMINI_1_5_FLASH,
        apiKey = apiKey,
        requestOptions = RequestOptions(timeout = CloudDeadline.millisFor(Strings.AiProcessors.GEMINI_CLOUD, settingsRepo))
    )

    override suspend fun rawPrompt(promptText: String, imageUri: String?): String? = withContext(Dispatchers.IO) {
        val apiKey = settingsRepo.getCredentialsSnapshot().forEngine(Strings.AiProcessors.GEMINI_CLOUD)
        if (apiKey.isNullOrBlank()) {
            Logger.log("Gemini API key not set — cannot use Gemini Cloud (rawPrompt)", TAG)
            return@withContext null
        }
        val model = boundedModel(apiKey)
        val bitmap = imageUri?.let { ImageAttachmentUtil.readBitmap(appContext, it) }
        try {
            CloudDeadline.run(Strings.AiProcessors.GEMINI_CLOUD, settingsRepo) {
                model.generateContent(content {
                    text(promptText)
                    bitmap?.let { image(it) }
                }).text
            }
        } catch (e: Exception) {
            Logger.log("Gemini Cloud rawPrompt failed: ${e.message}", TAG)
            null
        }
    }
}
