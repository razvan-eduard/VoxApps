package com.voxcommander.app.domain.intent.interpreter

import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * L2 Engine: Cloud-based AI interpretation using OpenAI API.
 * High intelligence, requires internet and API key.
 */
class OpenAiInterpreter(
    private val settingsRepo: SettingsRepository
) : AssistantEngine {

    private val TAG = Strings.Tags.OPENAI_INTERPRETER
    private val client = OkHttpClient()

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? = withContext(Dispatchers.IO) {
        val apiKey = settingsRepo.getApiKeySync()
        if (apiKey.isNullOrBlank()) {
            Logger.log("OpenAI API Key is missing", TAG)
            return@withContext null
        }

        val snapshot = settingsRepo.getSettingsSnapshot()
        val systemPrompt = PromptProvider.getNluSystemPrompt(snapshot, modelFilterLang, settingsRepo)
        val userPrompt = PromptProvider.formatUserInput(spokenText)

        val jsonBody = JSONObject().apply {
            put("model", Strings.Models.GPT_4O_MINI)
            put("temperature", 0.0) // Match precision
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
            })
            put("response_format", JSONObject().apply { put("type", "json_object") })
        }

        val request = Request.Builder()
            .url(Strings.Urls.OPENAI_CHAT_COMPLETIONS)
            .header("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val bodyString = response.body?.string()
            
            if (response.isSuccessful && bodyString != null) {
                val jsonResponse = JSONObject(bodyString)
                val content = jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                
                Logger.log("OpenAI Response: $content", TAG)
                return@withContext NluIntentParser.parse(content)
            } else {
                Logger.log("OpenAI API Error: ${response.code} - $bodyString", TAG)
            }
        } catch (e: Exception) {
            Logger.log("OpenAI Request Failed: ${e.message}", TAG)
        }
        null
    }
}
