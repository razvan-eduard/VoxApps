package com.voxapps.commander.domain.engine.whisper

import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.BaseVirtualEngine
import com.voxapps.commander.domain.engine.CloudDeadline
import com.voxapps.commander.domain.engine.ModelSpec
import com.voxapps.commander.domain.engine.SttEngine
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.utils.WavUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface WhisperApi {
    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part,
        @Part model: MultipartBody.Part,
        @Part language: MultipartBody.Part? = null
    ): WhisperResponse
}

data class WhisperResponse(val text: String)

class WhisperSttEngine(
    private val apiKey: String,
    private val settingsRepo: SettingsRepository,
    private val modelName: String = MODEL_NAME
) : BaseVirtualEngine(), SttEngine {

    override val engineKey: String = ENGINE_KEY

    private val api = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(
            // Retrofit's default client would apply OkHttp's own timeouts, which nothing here
            // chose. Same deadline as every other cloud call, read per request. See [CloudDeadline].
            OkHttpClient.Builder()
                .addInterceptor(CloudDeadline.interceptor(ENGINE_KEY, settingsRepo))
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WhisperApi::class.java)

    override suspend fun transcribe(audio: ByteArray, langCode: String?): String {
        val wavAudio = WavUtils.wrapPcmToWav(audio)
        val requestBody = wavAudio.toRequestBody(MEDIA_TYPE_WAV.toMediaType())
        val filePart = MultipartBody.Part.createFormData(PART_FILE, FILENAME_WAV, requestBody)
        val modelPart = MultipartBody.Part.createFormData(PART_MODEL, modelName)
        
        // Use provided language code to prevent hallucinating Slavic languages (e.g. Polish)
        val langPart = langCode?.let { 
            MultipartBody.Part.createFormData("language", it) 
        }
        
        return try {
            val response = CloudDeadline.run(ENGINE_KEY, settingsRepo) {
                api.transcribe(AUTH_PREFIX + apiKey, filePart, modelPart, langPart)
            } ?: return "Error: timed out"
            response.text
        } catch (e: Exception) {
            Logger.log("Whisper API transcription failed: ${e.message}", "WhisperSttEngine")
            "Error: ${e.message}"
        }
    }

    /**
     * Nothing is downloaded, so loading means checking the engine is configured — a credential must
     * be present. Deliberately no round-trip: a real API call at startup costs money, can rate-limit,
     * and tells a third party the app launched. An invalid key surfaces from an actual transcription.
     */
    override suspend fun unavailableReason(spec: ModelSpec): String? =
        if (apiKey.isNotBlank()) null else "API key is missing"

    companion object {
        const val ENGINE_KEY = "WHISPER_API"
        private const val BASE_URL = Strings.Urls.OPENAI_API
        private const val AUTH_PREFIX = "Bearer "
        private const val MEDIA_TYPE_WAV = "audio/wav"
        private const val FILENAME_WAV = "audio.wav"
        private const val PART_FILE = Strings.Api.PART_FILE
        const val PART_MODEL = Strings.Api.PART_MODEL
        const val MODEL_NAME = Strings.Models.WHISPER_1
    }
}
