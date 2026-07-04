package com.voxcommander.app.domain.engine.whisper

import com.voxcommander.app.domain.engine.SttEngine
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import com.voxcommander.app.utils.WavUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
    private val modelName: String = MODEL_NAME
) : SttEngine {
    
    private val api = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WhisperApi::class.java)

    override suspend fun transcribe(audio: ByteArray): String = transcribeWithLanguage(audio, null)

    suspend fun transcribeWithLanguage(audio: ByteArray, langCode: String?): String {
        val wavAudio = WavUtils.wrapPcmToWav(audio)
        val requestBody = wavAudio.toRequestBody(MEDIA_TYPE_WAV.toMediaType())
        val filePart = MultipartBody.Part.createFormData(PART_FILE, FILENAME_WAV, requestBody)
        val modelPart = MultipartBody.Part.createFormData(PART_MODEL, modelName)
        
        // Use provided language code to prevent hallucinating Slavic languages (e.g. Polish)
        val langPart = langCode?.let { 
            MultipartBody.Part.createFormData("language", it) 
        }
        
        return try {
            val response = api.transcribe(AUTH_PREFIX + apiKey, filePart, modelPart, langPart)
            response.text
        } catch (e: Exception) {
            Logger.log("Whisper API transcription failed: ${e.message}", "WhisperSttEngine")
            "Error: ${e.message}"
        }
    }

    override fun releaseHardware() {
        // No hardware resources to release for API engine
    }

    override fun releaseResources() {
        // API is persistent but doesn't hold large memory
    }

    companion object {
        private const val BASE_URL = Strings.Urls.OPENAI_API
        private const val AUTH_PREFIX = "Bearer "
        private const val MEDIA_TYPE_WAV = "audio/wav"
        private const val FILENAME_WAV = "audio.wav"
        private const val PART_FILE = Strings.Api.PART_FILE
        const val PART_MODEL = Strings.Api.PART_MODEL
        const val MODEL_NAME = Strings.Models.WHISPER_1
    }
}
