package com.voxapps.services

import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val client by lazy { OkHttpClient() }

    /** What a probe waits before deciding the service is not answering, when the caller says
     *  nothing. Callers that own a configured timeout pass it instead. */
    const val DEFAULT_TIMEOUT_SECONDS = 15

    /**
     * Awaits a network that can actually carry the request, when the app provides a way to ask.
     *
     * A probe fires when its card composes, which on a fresh launch races the network stack — the
     * request goes out before the system has validated a route, fails on the phone rather than the
     * service, and the card blames the endpoint. This module cannot observe connectivity itself
     * (that is app-side machinery), so the app installs the wait once at startup and every probe
     * gets it. Null — an app that never sets it — means probes behave as before.
     */
    @Volatile
    var awaitNetwork: (suspend () -> Unit)? = null

    /**
     * Cap on that wait. A phone that is genuinely offline must still produce a verdict rather than
     * a spinner: after this, the probe proceeds, fails on the socket, and reports offline.
     */
    private const val NETWORK_WAIT_MS = 3000L

    private suspend fun networkReady() {
        awaitNetwork?.let { withTimeoutOrNull(NETWORK_WAIT_MS) { it() } }
    }

    /**
     * Requests [spec]'s URL and reports whether it answered.
     *
     * [timeoutSeconds] is the same budget the caller's other outbound calls obey, so a silent
     * endpoint reports failure instead of leaving a spinner on screen.
     *
     * Note what a declaration can do here: the credential is sent to whatever host the schema names,
     * and these schemas can be served from a user-configured repository. The host is logged on every
     * probe for exactly that reason.
     */
    /** What a probe found: whether it answered, and — for HTTP — with what. */
    data class Outcome(val ok: Boolean, val code: Int? = null, val offline: Boolean = false) {
        /** The one status worth acting on: a credential the service will not accept. An OAuth token
         *  that has merely expired can often be refreshed, which is a different thing from a wrong
         *  key and needs to be told apart from "the network is down". */
        val rejected: Boolean get() = code == 401 || code == 403
    }

    /**
     * How long to wait before the one retry a name resolution gets.
     *
     * A probe runs when its card appears, which on a fresh launch can be a second after the process
     * started — before Wi-Fi has associated or DNS is answering. The request then fails on the name,
     * not on the service, and the card said "not reachable" about something it never reached. One
     * short retry covers the gap; a second would be a reconnection strategy, which is not this
     * object's job.
     */
    private const val OFFLINE_RETRY_DELAY_MS = 1500L

    suspend fun run(spec: ProbeSpec, timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS): Boolean =
        detailed(spec, timeoutSeconds).ok

    /** As [run], but reporting the status so a caller can tell a rejected credential from a failure. */
    suspend fun detailed(spec: ProbeSpec, timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS): Outcome {
        if (spec.missingCredential) {
            // No point spending a request to be told what is already known.
            Logger.log("${spec.id} needs a credential and has none — not probing", TAG)
            return Outcome(ok = false)
        }

        val request = buildRequest(spec) ?: run {
            Logger.log("${spec.id} declares an unusable probe URL: ${spec.url}", TAG)
            return Outcome(ok = false)
        }

        Logger.log("Probing ${spec.id} at ${request.url.host}", TAG)
        networkReady()

        var code: Int? = null
        var offline = false
        val call: suspend () -> Boolean = {
            client.newCall(request).execute().use { response ->
                code = response.code
                if (!response.isSuccessful) {
                    Logger.log("${spec.id} answered HTTP ${response.code}", TAG)
                }
                response.isSuccessful
            }
        }

        var ok = bounded(spec.id, timeoutSeconds, onUnresolvable = { offline = true }, block = call)

        // The service was never reached, so nothing was learned about it. Asked once more, because
        // the commonest reason is a network that came up a moment later — and reporting "not
        // reachable" for that blames the endpoint for the phone.
        if (!ok && offline) {
            Logger.log("${spec.id} could not be resolved — retrying once", TAG)
            kotlinx.coroutines.delay(OFFLINE_RETRY_DELAY_MS)
            offline = false
            ok = bounded(spec.id, timeoutSeconds, onUnresolvable = { offline = true }, block = call)
        }

        return Outcome(ok = ok, code = code, offline = !ok && offline)
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
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        probe: suspend () -> Boolean
    ): Boolean {
        Logger.log("Probing $id", TAG)
        networkReady()
        return bounded(id, timeoutSeconds) { probe() }
    }

    /**
     * What both kinds share: a deadline, one log line per failure, and any exception reported as a
     * plain false. Written once because it is the part that must not differ — a test that hangs is
     * a spinner that never stops, whichever kind of test it was.
     */
    private suspend fun bounded(
        id: String,
        timeoutSeconds: Int,
        /** Called when the failure was "no such host" — the network, not the service. */
        onUnresolvable: () -> Unit = {},
        block: suspend () -> Boolean
    ): Boolean = try {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutSeconds * 1000L) { block() } ?: run {
                Logger.log("$id exceeded its ${timeoutSeconds}s deadline — giving up on it", TAG)
                false
            }
        }
    } catch (e: java.net.UnknownHostException) {
        // Not a verdict on the service: the name never resolved, so nothing was asked of it.
        Logger.log("Probe for $id could not resolve its host: ${e.message}", TAG)
        onUnresolvable()
        false
    } catch (e: java.net.SocketException) {
        // Same class of failure by a different route: ConnectException and NoRouteToHostException
        // both extend this, and all of them mean the connection never left the phone — a route that
        // is not up yet, not a service that declined. A timeout is deliberately NOT here: it already
        // spent the full budget, so retrying it doubles the wait for a spinner someone is watching.
        Logger.log("Probe for $id never reached the network: ${e.message}", TAG)
        onUnresolvable()
        false
    } catch (e: Exception) {
        Logger.log("Probe for $id failed: ${e.message}", TAG)
        false
    }

    private fun buildRequest(spec: ProbeSpec): Request? {
        val credential = spec.credential.orEmpty()
        val withKey = spec.url.replace(ProbeSpec.KEY_PLACEHOLDER, credential)
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
