package com.voxapps.commander.domain.intent.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voxapps.commander.utils.AppScope
import com.voxapps.commander.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

object NewPipeExtractorHelper {

    private const val TAG = "NewPipeExtractor"
    private const val DUMMY_VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Volatile
    private var initialized = false

    @Volatile
    private var warmedUp = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private class OkHttpDownloader : Downloader() {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")

            val method = request.httpMethod()
            val dataToSend = request.dataToSend()
            if (method.equals("POST", ignoreCase = true)) {
                val body = if (dataToSend != null) {
                    okhttp3.RequestBody.create(null, dataToSend)
                } else {
                    okhttp3.RequestBody.create(null, ByteArray(0))
                }
                builder.post(body)
            } else {
                builder.method(method, null)
            }

            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }

            val response = client.newCall(builder.build()).execute()
            val body = response.body?.string()
            val url = response.request.url.toString()

            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }

            return org.schabi.newpipe.extractor.downloader.Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                url
            )
        }
    }

    fun initIfNeeded() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(OkHttpDownloader())
            initialized = true
            Logger.log("NewPipe Extractor initialized", TAG)
        }
    }

    fun warmUp() {
        if (warmedUp) return
        AppScope.io.launch {
            try {
                initIfNeeded()
                Logger.log("NewPipe warmup started — fetching base.js (first query is slowest)", TAG)
                StreamInfo.getInfo(DUMMY_VIDEO_URL)
                warmedUp = true
                Logger.log("NewPipe warmup complete — Rhino engine ready", TAG)
            } catch (e: Exception) {
                Logger.log("NewPipe warmup failed (silent): ${e.message}", TAG)
            }
        }
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            initIfNeeded()
            val searchExtractor = ServiceList.YouTube.getSearchExtractor("test")
            searchExtractor.fetchPage()
            val items = searchExtractor.initialPage?.items ?: emptyList()
            val found = items.any { it.url?.contains("watch?v=") == true }
            if (found) Logger.log("NewPipe connection test OK — search returned video results", TAG)
            else Logger.log("NewPipe connection test failed — no video results", TAG)
            found
        } catch (e: Exception) {
            Logger.log("NewPipe connection test failed: ${e.message}", TAG)
            false
        }
    }

    suspend fun searchAndPlay(context: Context, query: String, targetPackage: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            initIfNeeded()
            val youtube = ServiceList.YouTube
            val searchExtractor = youtube.getSearchExtractor(query)
            searchExtractor.fetchPage()

            val items = searchExtractor.initialPage?.items ?: emptyList()
            val firstVideo = items.firstOrNull { it.url?.contains("watch?v=") == true }

            if (firstVideo == null) {
                Logger.log("NewPipe search returned no results for: $query", TAG)
                return@withContext false
            }

            val videoUrl = firstVideo.url
            val videoId = extractVideoId(videoUrl)
            Logger.log("NewPipe search found: $videoId for query: $query", TAG)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://youtu.be/$videoId")
                if (targetPackage != null) setPackage(targetPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Logger.log("Launched ${targetPackage ?: "default"} with video: $videoId", TAG)
            true
        } catch (e: Exception) {
            Logger.log("NewPipe searchAndPlay failed: ${e.message}", TAG)
            false
        }
    }

    private fun extractVideoId(url: String?): String? {
        if (url == null) return null
        return try {
            val uri = Uri.parse(url)
            uri.getQueryParameter("v")
        } catch (e: Exception) {
            null
        }
    }
}
