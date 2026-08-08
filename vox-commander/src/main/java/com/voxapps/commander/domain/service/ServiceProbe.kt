package com.voxapps.commander.domain.service

import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.engine.CloudDeadline
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks a [ProbeSpec] whether its service answers — the one implementation, for every family.
 *
 * There were three. A search provider ran a real query with a dummy term and then special-cased
 * OpenAI to list models instead, because a GET against chat-completions can only fail; an engine
 * probed a declared URL; an API integration was not tested at all and reported success from a token
 * sitting in local storage. Three answers to one question, and the one that mattered most —
 * "is this token still accepted?" — was the one nobody asked.
 *
 * Deliberately separate from loading an engine. Loading happens at startup and on every switch, so
 * it must not make a request: a real call there costs money, can rate-limit the user, and tells a
 * third party the app launched. Loading checks only that a credential is *present*, which cannot
 * tell a valid key from a mistyped one. This closes that gap at the moment it is worth a request —
 * when someone asks.
 */
object ServiceProbe {

    private const val TAG = "ServiceProbe"
    private const val KEY_PLACEHOLDER = "{key}"

    private val client by lazy { OkHttpClient() }

    /**
     * Requests [spec]'s URL and reports whether it answered.
     *
     * [settingsRepo] is used only for the deadline, which is the same one every other outbound call
     * obeys, so a silent endpoint reports failure instead of leaving a spinner on screen.
     *
     * Note what a declaration can do here: the credential is sent to whatever host the schema names,
     * and these schemas can be served from a user-configured repository. The host is logged on every
     * probe for exactly that reason.
     */
    suspend fun run(spec: ProbeSpec, settingsRepo: SettingsRepository): Boolean {
        if (spec.missingCredential) {
            // No point spending a request to be told what is already known.
            Logger.log("${spec.id} needs a credential and has none — not probing", TAG)
            return false
        }

        val request = buildRequest(spec) ?: run {
            Logger.log("${spec.id} declares an unusable probe URL: ${spec.url}", TAG)
            return false
        }

        Logger.log("Probing ${spec.id} at ${request.url.host}", TAG)

        return bounded(spec.id, settingsRepo) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("${spec.id} answered HTTP ${response.code}", TAG)
                }
                response.isSuccessful
            }
        }
    }

    /**
     * The same test for a service that has no URL to call.
     *
     * NewPipe is the case: a library driving YouTube, whose test is a real search whose *results*
     * are inspected — an answer no HTTP status can give, since scraping breaks while the host keeps
     * returning 200. Routed through here so it is bounded and reported like every other test, and so
     * the UI never has to know which kind of thing it is showing.
     */
    suspend fun run(
        id: String,
        settingsRepo: SettingsRepository,
        probe: suspend () -> Boolean
    ): Boolean {
        Logger.log("Probing $id", TAG)
        return bounded(id, settingsRepo) { probe() }
    }

    /**
     * What both kinds share: a deadline, one log line per failure, and any exception reported as a
     * plain false. Written once because it is the part that must not differ — a test that hangs is
     * a spinner that never stops, whichever kind of test it was.
     */
    private suspend fun bounded(
        id: String,
        settingsRepo: SettingsRepository,
        block: suspend () -> Boolean
    ): Boolean = try {
        withContext(Dispatchers.IO) {
            CloudDeadline.run(id, settingsRepo) { block() } ?: false
        }
    } catch (e: Exception) {
        Logger.log("Probe for $id failed: ${e.message}", TAG)
        false
    }

    private fun buildRequest(spec: ProbeSpec): Request? {
        val credential = spec.credential.orEmpty()
        val withKey = spec.url.replace(KEY_PLACEHOLDER, credential)
        val parsed = withKey.toHttpUrlOrNull() ?: return null

        val url = when (val auth = spec.auth) {
            // Appending rather than setting, so a declaration that already carries the parameter
            // (via {key}) is not given it twice.
            is ProbeSpec.AuthStyle.Query ->
                if (parsed.queryParameter(auth.param) != null) parsed
                else parsed.newBuilder().addQueryParameter(auth.param, credential).build()
            else -> parsed
        }

        return Request.Builder()
            .url(url)
            .apply {
                if (spec.auth is ProbeSpec.AuthStyle.Bearer && credential.isNotBlank()) {
                    header("Authorization", "Bearer $credential")
                }
            }
            .get()
            .build()
    }
}
