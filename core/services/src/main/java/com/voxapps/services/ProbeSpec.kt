package com.voxapps.services

import com.voxapps.logging.Logger

/**
 * Everything needed to ask a remote service "are you there, and do you accept this credential?".
 *
 * One type for every family of service the app declares — engines, search providers, API
 * integrations, media backends. They arrived with three vocabularies and three separate tests: a
 * search provider ran a real query with a dummy term and then special-cased OpenAI to list models
 * instead; an engine probed a declared URL; an integration was never tested at all and reported
 * "connected" from the presence of a token in local storage. The differences were in the words, not
 * in the question.
 *
 * [credential] is already resolved by the caller — this type never reaches into settings, so a probe
 * cannot be told to read a credential the caller did not intend to hand over.
 */
data class ProbeSpec(
    /** For logging, so a failing probe names the thing that failed rather than a bare URL. */
    val id: String,
    /** The URL that answers cheaply. `{key}` anywhere in it is replaced by [credential]. */
    val url: String,
    val auth: AuthStyle = AuthStyle.None,
    val credential: String? = null
) {
    /**
     * How the credential attaches to the request.
     *
     * A closed set, and deliberately so: a declaration chooses among these, it does not describe a
     * new one. What a schema supplies is data — a URL, a parameter name — never a way of executing
     * something the app has not compiled.
     */
    sealed interface AuthStyle {
        /** Public endpoint, or the credential is already inside the URL via `{key}`. */
        data object None : AuthStyle

        /** `Authorization: Bearer <credential>` — OpenAI, and any OAuth access token. */
        data object Bearer : AuthStyle

        /** Appended as `?<param>=<credential>` — Gemini, WeatherAPI and most key-in-URL services. */
        data class Query(val param: String) : AuthStyle
    }

    /**
     * True when this service needs a credential it has not been given, so probing is pointless.
     *
     * Covers both ways a credential can be needed: an [auth] style that attaches one to the
     * request, and a `{key}` placeholder written into the URL itself — the escape hatch for a
     * service that takes its key in the path, which no header or query parameter can describe.
     */
    val missingCredential: Boolean
        get() = (auth != AuthStyle.None || url.contains(KEY_PLACEHOLDER)) && credential.isNullOrBlank()

    companion object {
        /** Replaced by the credential wherever it appears in a URL. Also declares, by its presence,
         *  that the service needs one — see [missingCredential]. */
        const val KEY_PLACEHOLDER = "{key}"

        /**
         * Builds a spec from a declaration, resolving [probeUrl] against [endpoint].
         *
         * A declaration should not repeat itself. `endpoint` already says where the service lives,
         * so the probe says only what is different about it:
         *
         *  - omitted      → probe [endpoint] itself, for a service whose own URL answers a GET;
         *  - `models`     → appended to the endpoint, for a service whose base URL is a base;
         *  - `/v1/models` → from the host root, for an endpoint that is already a full path;
         *  - `?q=London`  → the endpoint with a query, for a service that answers nothing without
         *                   arguments — every search API, whose bare endpoint returns 400.
         *
         * These are ordinary relative-URL resolution, and each is needed by something: Spotify's
         * endpoint is a base (`…/v1`) so its probe hangs off it; a search provider's endpoint is the
         * working call itself (`…/v1/chat/completions`) and its cheap sibling is `/v1/models`; a
         * weather endpoint is complete already and needs only arguments.
         *
         * **A probe is always a path, never a URL.** An absolute one is refused, and that is a
         * security boundary rather than a style rule: these schemas can be served from a repository
         * the user configured, the probe carries the service's credential, and a path can only ever
         * reach the host `endpoint` already names. Nothing in practice needs more — the working
         * endpoints are POSTs, so their probes are siblings like `/models`, `/me` and `/health`.
         *
         * Returns null when there is nothing to probe, which is how "not testable" is expressed —
         * by the absence of a declaration rather than by a list in code.
         */
        fun from(
            id: String,
            endpoint: String?,
            probeUrl: String? = null,
            auth: AuthStyle = AuthStyle.None,
            credential: String? = null
        ): ProbeSpec? {
            if (probeUrl != null && (probeUrl.startsWith("http://") || probeUrl.startsWith("https://"))) {
                Logger.log("$id declares an absolute probe URL, which is not allowed: $probeUrl", TAG)
                return null
            }

            val base = endpoint?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
            val url = when {
                probeUrl.isNullOrBlank() -> base
                probeUrl.startsWith("?") -> base + probeUrl
                probeUrl.startsWith("/") -> originOf(base) + probeUrl
                else -> "$base/$probeUrl"
            }

            return ProbeSpec(id = id, url = url, auth = auth, credential = credential)
        }

        /** Scheme and host of [url], so a root-relative probe stays on the declared host. */
        private fun originOf(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val pathStart = url.indexOf('/', schemeEnd + 3)
            return if (pathStart < 0) url else url.substring(0, pathStart)
        }

        private const val TAG = "ProbeSpec"
    }
}
