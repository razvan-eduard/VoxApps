package com.voxapps.calendarapp.domain.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Fetches a user-subscribed calendar's .ics file. `vox-calendar` has no other network code — this is
 * the app's one deliberate, narrow exception to being otherwise fully offline (see the `INTERNET`
 * permission's manifest comment).
 *
 * No certificate pinning: pinning needs a fixed, known-in-advance certificate/public key, which is
 * incompatible with an arbitrary user-supplied subscription URL that could be any provider (Google,
 * Outlook, a public-holidays feed, ...) — standard OkHttp/platform TLS trust-store validation
 * (unmodified here) is the correct mechanism. HTTPS is required outright; plain `http://` is rejected,
 * since this is inherently fetching from the open internet.
 */
object IcsUrlFetcher {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** `webcal://`/`webcals://` has no transport of its own — every real client (Google/Apple/
     *  Thunderbird) treats it as an alias for `https://`, so it's rewritten here before the request. */
    suspend fun fetch(rawUrl: String): InputStream = withContext(Dispatchers.IO) {
        val httpUrl = rawUrl.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
        require(httpUrl.startsWith("https://")) { "Only https:// (or webcal(s)://) URLs are supported" }
        val request = Request.Builder().url(httpUrl).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code}")
        }
        // Buffered fully into memory before returning — an .ics feed is at most a few MB, and the
        // caller's Biweekly.parse needs the stream to outlive this response's own scope.
        val bytes = response.body?.byteStream()?.use { it.readBytes() } ?: run {
            response.close()
            error("Empty response body")
        }
        response.close()
        bytes.inputStream()
    }
}
