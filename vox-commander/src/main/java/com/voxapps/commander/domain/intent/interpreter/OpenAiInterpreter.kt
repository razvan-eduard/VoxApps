package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.utils.Logger
import com.voxapps.commander.utils.Strings
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
    private val appContext: Context,
    private val settingsRepo: SettingsRepository
) : AssistantEngine {

    private val TAG = Strings.Tags.OPENAI_INTERPRETER
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? = withContext(Dispatchers.IO) {
        val apiKey = settingsRepo.getApiKeySync()
        if (apiKey.isNullOrBlank()) {
            Logger.log("OpenAI API Key is missing", TAG)
            return@withContext null
        }

        val snapshot = settingsRepo.getSettingsSnapshot()
        val systemPrompt = PromptProvider.getNluSystemPrompt(snapshot, modelFilterLang, settingsRepo)
        val userPrompt = PromptProvider.formatUserInput(spokenText)

        val content = sendChatCompletion(apiKey, systemPrompt, userPrompt, forceJson = true) ?: return@withContext null
        Logger.log("OpenAI Response: $content", TAG)
        NluIntentParser.parse(content)
    }

    override suspend fun rawPrompt(promptText: String, imageUri: String?): String? = withContext(Dispatchers.IO) {
        val apiKey = settingsRepo.getApiKeySync()
        if (apiKey.isNullOrBlank()) {
            Logger.log("OpenAI API Key is missing (rawPrompt)", TAG)
            return@withContext null
        }
        sendChatCompletion(apiKey, systemPrompt = null, userPrompt = promptText, forceJson = false, imageUri = imageUri)
    }

    /** Shared "send prompt, get raw text" call used by both [processCommand] and [rawPrompt]. [imageUri],
     *  when present, is attached as an additional `image_url` content part on the user message —
     *  requires Commander to already hold a granted read permission on it (the caller's job). */
    private fun sendChatCompletion(
        apiKey: String, systemPrompt: String?, userPrompt: String, forceJson: Boolean, imageUri: String? = null
    ): String? {
        val userContent: Any = imageUri?.let { ImageAttachmentUtil.readAsBase64DataUri(appContext, it) }?.let { dataUri ->
            JSONArray().apply {
                put(JSONObject().apply { put("type", "text"); put("text", userPrompt) })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply { put("url", dataUri) })
                })
            }
        } ?: userPrompt

        val jsonBody = JSONObject().apply {
            put("model", Strings.Models.GPT_4O_MINI)
            put("temperature", 0.0) // Match precision
            put("messages", JSONArray().apply {
                systemPrompt?.let { put(JSONObject().apply { put("role", "system"); put("content", it) }) }
                put(JSONObject().apply { put("role", "user"); put("content", userContent) })
            })
            if (forceJson) put("response_format", JSONObject().apply { put("type", "json_object") })
        }

        val request = Request.Builder()
            .url(Strings.Urls.OPENAI_CHAT_COMPLETIONS)
            .header("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val bodyString = response.body?.string()

            if (response.isSuccessful && bodyString != null) {
                val jsonResponse = JSONObject(bodyString)
                jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else {
                Logger.log("OpenAI API Error: ${response.code} - $bodyString", TAG)
                null
            }
        } catch (e: Exception) {
            Logger.log("OpenAI Request Failed: ${e.message}", TAG)
            null
        }
    }
}
