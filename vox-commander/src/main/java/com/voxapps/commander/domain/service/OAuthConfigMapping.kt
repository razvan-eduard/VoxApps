package com.voxapps.commander.domain.service

import com.voxapps.commander.service.OAuthConfig
import com.voxapps.services.AuthDeclaration

/**
 * The OAuth client config an [AuthDeclaration] describes, or null when it describes something else.
 *
 * An extension rather than a member, because the declaration is shared vocabulary and [OAuthConfig]
 * is this app's own client: the schema says which flow a service speaks and where its endpoints are,
 * and turning that into something an OAuth implementation accepts is the consuming app's business.
 *
 * Built here because two callers were building it identically from the same fields — the audio
 * playback path and the integrations screen — and a third would have copied it again.
 */
fun AuthDeclaration.toOAuthConfig(serviceId: String): OAuthConfig? {
    if (!isOAuth) return null
    val authorize = authorizeUrl ?: return null
    val token = tokenUrl ?: return null
    val redirect = redirectUri ?: return null
    return OAuthConfig(
        serviceId = serviceId,
        authorizeUrl = authorize,
        tokenUrl = token,
        redirectUri = redirect,
        scopes = scopes.orEmpty(),
        // The flow, from whichever field carries it: `flow` today, `type` in a copy written before
        // it existed (`oauth2_pkce` / `oauth2_authorization_code`). Reading `style` alone would put
        // every service on PKCE, since it says only "oauth2".
        usePkce = !(flow ?: type ?: style).orEmpty().contains(AuthDeclaration.FLOW_AUTHORIZATION_CODE)
    )
}
