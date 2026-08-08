package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import com.voxapps.commander.data.local.dao.FastMapDao
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.CloudDeadline
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.logging.Logger
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
    private val settingsRepo: SettingsRepository,
    private val fastMapDao: FastMapDao
) : AssistantEngine {

    private val TAG = Strings.Tags.OPENAI_INTERPRETER

    /** Set right before [rawPrompt] returns null, so [LlmHookEngineSelector] can build an accurate
     *  error message instead of a hardcoded, potentially-wrong guess — a 500 `server_error` (OpenAI's
     *  own infrastructure having an issue) is not the same failure as a 401 (an actually-bad key), but
     *  [rawPrompt]'s `String?` return can't distinguish them without a side channel like this one. Not
     *  thread-safe against concurrent calls on the same instance, which is fine here: this hook path
     *  only ever has one in-flight request at a time per engine (see [LlmHookWorker]). */
    var lastErrorReason: String? = null
        private set

    /** Timeouts come from the interceptor, per call, rather than from the builder: the number is a
     *  live setting and this client is built once. See [CloudDeadline]. */
    private val client = OkHttpClient.Builder()
        .addInterceptor(CloudDeadline.interceptor(Strings.AiProcessors.OPENAI, settingsRepo))
        .build()

    override suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent? = withContext(Dispatchers.IO) {
        val apiKey = settingsRepo.getCredentialsSnapshot().forEngine(Strings.AiProcessors.OPENAI)
        if (apiKey.isNullOrBlank()) {
            Logger.log("OpenAI API Key is missing", TAG)
            return@withContext null
        }

        val snapshot = settingsRepo.getSettingsSnapshot()
        val activeRules = fastMapDao.getAllRulesOnce().filter { it.isActive }
        val systemPrompt = PromptProvider.getNluSystemPrompt(spokenText, snapshot, modelFilterLang, settingsRepo, activeRules, Strings.AiProcessors.OPENAI)
        val userPrompt = PromptProvider.formatUserInput(spokenText)

        val content = sendChatCompletion(apiKey, systemPrompt, userPrompt, forceJson = true) ?: return@withContext null
        Logger.log("OpenAI Response: $content", TAG)
        NluIntentParser.parse(content)
    }

    override suspend fun rawPrompt(promptText: String, imageUri: String?): String? = withContext(Dispatchers.IO) {
        val apiKey = settingsRepo.getCredentialsSnapshot().forEngine(Strings.AiProcessors.OPENAI)
        if (apiKey.isNullOrBlank()) {
            lastErrorReason = "API key is missing"
            Logger.log("OpenAI API Key is missing (rawPrompt)", TAG)
            return@withContext null
        }
        sendChatCompletion(apiKey, systemPrompt = null, userPrompt = promptText, forceJson = false, imageUri = imageUri)
    }

    /** Shared "send prompt, get raw text" call used by both [processCommand] and [rawPrompt]. [imageUri],
     *  when present, is attached as an additional `image_url` content part on the user message —
     *  requires Commander to already hold a granted read permission on it (the caller's job). */
    private suspend fun sendChatCompletion(
        apiKey: String, systemPrompt: String?, userPrompt: String, forceJson: Boolean, imageUri: String? = null
    ): String? {
        // Cleared per call: the reason is read after a failure, and a stale one from a previous
        // request would be reported as this request's.
        lastErrorReason = null

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

        // Bounded so a slow or silent service cannot hold the command: whoever asked gets an answer
        // — here, a null that sends the cascade on to the user's fallback — within the deadline,
        // even though the blocking call underneath is released by the interceptor a moment later.
        return CloudDeadline.run(Strings.AiProcessors.OPENAI, settingsRepo) {
            executeChatCompletion(request)
        } ?: run {
            if (lastErrorReason == null) lastErrorReason = "Timed out waiting for a response"
            null
        }
    }

    private fun executeChatCompletion(request: Request): String? {
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
                // 401/403 is a genuinely bad/revoked key; 429 is a quota/rate-limit issue (also not
                // fixed by re-checking the key); anything else (5xx in particular — confirmed
                // on-device: a plain OpenAI-side "server_error", nothing to do with the key at all) is
                // an OpenAI-side problem, not a local misconfiguration — surfaced as such rather than
                // guessing "check API key" for every failure the way this used to.
                lastErrorReason = when (response.code) {
                    401, 403 -> "API key is invalid or revoked"
                    429 -> "Rate limit or quota exceeded"
                    in 500..599 -> "OpenAI server error (HTTP ${response.code}) — usually transient, try again shortly"
                    else -> "HTTP ${response.code}"
                }
                Logger.log("OpenAI API Error: ${response.code} - $bodyString", TAG)
                null
            }
        } catch (e: Exception) {
            lastErrorReason = "Network error: ${e.message}"
            Logger.log("OpenAI Request Failed: ${e.message}", TAG)
            null
        }
    }
}
