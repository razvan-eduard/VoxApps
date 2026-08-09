package com.voxapps.services

import com.google.gson.annotations.SerializedName

/**
 * How a declared service authenticates — one shape, parsed from every schema that describes one.
 *
 * `models.json`, `virtual_models.json`, `search_definitions.json` and `api_integrations.json` all
 * describe services that need a credential, and each said so differently: a capability plus an
 * implied bearer header, a `{apiKey}` placeholder buried in a query template, and an `auth` block
 * with a `type`. The same three facts every time — how the credential attaches, what it is called
 * when it goes in the URL, and where to obtain one — written three ways, so the code that used them
 * was written three times too.
 *
 * [style] is a closed set the app implements ([ProbeSpec.AuthStyle]); a declaration selects among
 * them rather than describing a new one. Everything else here is data — which is why this lives
 * beside the prober rather than beside any one app's OAuth client: the fields describe the service,
 * and turning them into a client config is the consuming app's business.
 */
data class AuthDeclaration(
    /** `bearer` | `query` | `oauth2` | `none`. Read from `type` as well, for copies written before
     *  the vocabulary was shared — a schema can be served from a repository that has not caught up. */
    val style: String? = null,
    val type: String? = null,

    /** Which OAuth flow, when [style] is `oauth2`: `pkce` | `authorization_code`.
     *
     *  Separate from [style] because they answer different questions — the family decides how the
     *  credential attaches to a request, the flow decides how it was obtained. They used to share
     *  the `type` field (`oauth2_pkce`), which meant the variant lived in the field documented as
     *  the outdated spelling, and reading the vocabulary's own `style` alone put every service on
     *  PKCE regardless of what it declared. */
    val flow: String? = null,

    /** Parameter name when the credential travels in the URL: `key` for Gemini and WeatherAPI. */
    val param: String? = null,

    @SerializedName("authorize_url") val authorizeUrl: String? = null,
    @SerializedName("token_url") val tokenUrl: String? = null,
    @SerializedName("redirect_uri") val redirectUri: String? = null,
    val scopes: String? = null,

    /** The user must supply a client id before authorising. True for Spotify; most services do not
     *  ask for one, and the field exists so the screen can omit that input rather than show an empty
     *  box nobody can fill. */
    @SerializedName("requires_client_id") val requiresClientId: Boolean = false,

    /** Where the user creates the application that issues [requiresClientId] — a developer portal. */
    @SerializedName("client_id_url") val clientIdUrl: String? = null,

    /** Translation keys for the setup steps, shown in order. Keys rather than sentences, so the
     *  instructions stay translated; the redirect URI to paste comes from [redirectUri] itself
     *  rather than being written out again in the dialog. */
    @SerializedName("setup_help_keys") val setupHelpKeys: List<String> = emptyList()
) {
    /** The declared style, falling back to the older `type` spelling, then to none. */
    val effectiveStyle: String get() = (style ?: type ?: STYLE_NONE).lowercase()

    val isOAuth: Boolean get() = effectiveStyle.startsWith(STYLE_OAUTH2)

    /**
     * Translated into the closed set the prober implements.
     *
     * OAuth resolves to a bearer token, because that is what an access token is once obtained — the
     * flow that produced it is not the prober's concern.
     */
    fun probeStyle(): ProbeSpec.AuthStyle = when {
        isOAuth -> ProbeSpec.AuthStyle.Bearer
        effectiveStyle == STYLE_BEARER -> ProbeSpec.AuthStyle.Bearer
        effectiveStyle == STYLE_QUERY -> ProbeSpec.AuthStyle.Query(param ?: DEFAULT_QUERY_PARAM)
        else -> ProbeSpec.AuthStyle.None
    }

    companion object {
        const val STYLE_NONE = "none"
        const val STYLE_BEARER = "bearer"
        const val STYLE_QUERY = "query"
        const val STYLE_OAUTH2 = "oauth2"

        const val FLOW_PKCE = "pkce"
        const val FLOW_AUTHORIZATION_CODE = "authorization_code"

        /** What almost every key-in-URL service calls it, so a declaration need not repeat it. */
        const val DEFAULT_QUERY_PARAM = "key"
    }
}
