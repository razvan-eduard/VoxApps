package com.voxcommander.app.domain.intent.handler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.service.MediaSessionListenerService
import com.voxcommander.app.service.SpotifyPkceManager
import com.voxcommander.app.service.SpotifyRemoteManager
import com.voxcommander.app.service.SpotifyWebApi
import com.voxcommander.app.utils.Logger

/**
 * Handles "custom" domain intents from FastMap rules.
 * Fires the intentAction specified in the rule (e.g. MEDIA_PLAY_FROM_SEARCH)
 * with the query as an extra. Falls back to simple launch if no action specified.
 */
class GenericLaunchHandler : IntentHandler {

    override fun canHandle(intent: NluIntent): Boolean {
        return intent.domain == "custom"
    }

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        val pkg = intent.targetApp ?: resolvedApp?.packageName
        if (pkg.isNullOrBlank()) {
            Logger.log("GenericLaunchHandler: no target package", TAG)
            return false
        }

        val query = intent.logicalSubject
        val action = intent.intentAction

        if (action.isNullOrBlank()) {
            // No specific action — just launch the app
            return launchApp(context, pkg)
        }

        // Try the specified intent action
        return when (action) {
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> playFromSearch(context, pkg, query)
            Intent.ACTION_VIEW -> {
                if (!query.isNullOrBlank() && pipedPlayDirect(context, pkg, query)) return true
                viewSearch(context, pkg, resolvedApp, query, intent.uriTemplate)
            }
            Intent.ACTION_WEB_SEARCH -> webSearch(context, pkg, query)
            Intent.ACTION_SEARCH -> {
                if (!query.isNullOrBlank() && pipedPlayDirect(context, pkg, query)) return true
                browserSearch(context, pkg, resolvedApp, query)
            }
            else -> {
                if (!query.isNullOrBlank() && pipedPlayDirect(context, pkg, query)) return true
                // Generic: try to fire the action with query as SearchManager.QUERY extra
                fireGenericAction(context, pkg, action, query)
            }
        }
    }

    /**
     * Approach:
     * 1. For Spotify: try SpotifyRemoteManager (App Remote SDK) — direct play from search
     * 2. Fallback: intent with EXTRA_MEDIA_FOCUS + EXTRA_MEDIA_ARTIST
     * 3. Fallback: plain intent
     * 4. Last resort: just launch the app
     */
    private fun playFromSearch(context: Context, pkg: String, query: String?): Boolean {
        if (query.isNullOrBlank()) {
            return launchApp(context, pkg)
        }

        // 0. Try Piped API direct play first (works for any app that handles youtu.be URLs)
        if (pipedPlayDirect(context, pkg, query)) return true

        // 1. For Spotify, try Web API first (uses PKCE token)
        if (pkg == "com.spotify.music") {
            if (SpotifyPkceManager.isAuthorized) {
                val clientId = SpotifyRemoteManager.getClientId()
                if (clientId != null && SpotifyWebApi.playSearch(clientId, query)) {
                    Logger.log("playFromSearch via Spotify Web API succeeded", TAG)
                    return true
                }
                Logger.log("Spotify Web API failed, falling back to intent", TAG)
            } else {
                Logger.log("Spotify not authorized (PKCE), falling back to intent", TAG)
            }
        }

        // 2. Try intent with EXTRA_MEDIA_FOCUS (artist)
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
            putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        Logger.log("Sending playFromSearch intent with EXTRA_MEDIA_FOCUS=artist for $pkg, query=$query", TAG)
        if (tryLaunch(context, playIntent)) {
            Logger.log("playFromSearch intent sent for $pkg", TAG)
            return true
        }

        // 3. Fallback: plain intent without extras
        Logger.log("Focused intent failed, trying plain intent for $pkg", TAG)
        val plainIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (tryLaunch(context, plainIntent)) return true

        // 4. Last resort: just launch the app
        return launchApp(context, pkg)
    }

    /**
     * Piped "play direct": searches via Piped API, gets first video,
     * opens it as youtu.be URL that the target app intercepts and plays.
     */
    private fun pipedPlayDirect(context: Context, pkg: String?, query: String): Boolean {
        Logger.log("Piped play direct: searching for '$query' on $pkg", TAG)
        return try {
            kotlinx.coroutines.runBlocking {
                PipedSearchHelper.searchAndPlay(context, query, pkg)
            }
        } catch (e: Exception) {
            Logger.log("Piped play direct failed: ${e.message}", TAG)
            false
        }
    }

    /**
     * Browser search using the target browser app.
     * Tries: 1) URI template from AppRegistry, 2) ACTION_WEB_SEARCH with package,
     * 3) Google search URL via ACTION_VIEW, 4) just launch the app.
     */
    private fun browserSearch(context: Context, pkg: String, resolvedApp: AppRegistry.AppEntry?, query: String?): Boolean {
        if (query.isNullOrBlank()) {
            return launchApp(context, pkg)
        }

        // 1. Try URI template if available
        val searchTemplate = resolvedApp?.uriTemplates?.get(AppRegistry.TemplateActions.SEARCH)
        if (searchTemplate != null) {
            val uri = searchTemplate.replace(AppRegistry.TemplateParams.QUERY, Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(uri)
                setPackage(pkg)
                putExtra(Intent.EXTRA_REFERRER, "android-app://com.voxcommander.app")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (tryLaunch(context, intent)) return true
        }

        // 2. Try ACTION_WEB_SEARCH with the target package
        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage(pkg)
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (tryLaunch(context, webSearchIntent)) return true

        // 3. Fallback: Google search URL via ACTION_VIEW with target package
        val googleUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(googleUrl)
            setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (tryLaunch(context, viewIntent)) return true

        // 4. Fallback: Google search URL without package (system default browser)
        val implicitIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(googleUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (tryLaunch(context, implicitIntent)) return true

        // 5. Last resort: just launch the app
        return launchApp(context, pkg)
    }

    private fun viewSearch(context: Context, pkg: String, resolvedApp: AppRegistry.AppEntry?, query: String?, uriTemplate: String? = null): Boolean {
        // Use URI template: passed-in template first, then resolvedApp.uriTemplates
        val searchTemplate = uriTemplate ?: resolvedApp?.uriTemplates?.get(AppRegistry.TemplateActions.SEARCH)
        if (searchTemplate != null && query != null) {
            val uri = searchTemplate.replace(AppRegistry.TemplateParams.QUERY, Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(uri)
                setPackage(pkg)
                putExtra(Intent.EXTRA_REFERRER, "android-app://com.voxcommander.app")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (tryLaunch(context, intent)) return true
        }
        return launchApp(context, pkg)
    }

    /**
     * Web search using the browser's default search engine.
     * Sends ACTION_WEB_SEARCH with SearchManager.QUERY — no setPackage,
     * so the system's default search handler (browser) picks it up and
     * uses whatever search engine the user configured (Google, DuckDuckGo, etc.).
     * Falls back to launching the target app if no global search handler exists.
     */
    private fun webSearch(context: Context, pkg: String, query: String?): Boolean {
        if (query.isNullOrBlank()) {
            return launchApp(context, pkg)
        }

        // 1. Try global ACTION_WEB_SEARCH (uses browser's default search engine)
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (tryLaunch(context, searchIntent)) return true

        // 2. Fallback: launch the target app with query as extra
        return fireGenericAction(context, pkg, Intent.ACTION_WEB_SEARCH, query)
    }

    private fun fireGenericAction(context: Context, pkg: String, action: String, query: String?): Boolean {
        val intent = Intent(action).apply {
            setPackage(pkg)
            if (query != null) {
                putExtra(android.app.SearchManager.QUERY, query)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return tryLaunch(context, intent)
    }

    private fun launchApp(context: Context, pkg: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return tryLaunch(context, launchIntent)
        }
        Logger.log("GenericLaunchHandler: no launch intent for $pkg", TAG)
        return false
    }

    private fun tryLaunch(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Logger.log("GenericLaunchHandler: failed to launch: ${e.message}", TAG)
            false
        }
    }

    companion object {
        private const val TAG = "GenericLaunchHandler"
    }
}
