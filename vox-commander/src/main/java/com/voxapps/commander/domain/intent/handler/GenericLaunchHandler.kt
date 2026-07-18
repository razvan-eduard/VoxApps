package com.voxapps.commander.domain.intent.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.utils.IntentUtils
import com.voxapps.commander.utils.Logger

/**
 * Catch-all handler (registered LAST in IntentRouter). Runs only for intents no specific handler
 * (Search/Audio/Navigation/System/Messaging) claimed — i.e. the "custom" domain from FastMap rules
 * AND any custom-category domain from DefaultApps. Fires the rule's intentAction with the query as
 * an extra, or just launches the resolved default app when no action is specified.
 */
class GenericLaunchHandler : IntentHandler {

    override fun canHandle(intent: NluIntent): Boolean = true

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        val pkg = intent.targetApp ?: resolvedApp?.packageName
        if (pkg.isNullOrBlank()) {
            Logger.log("GenericLaunchHandler: no target package", TAG)
            return false
        }

        val query = intent.logicalSubject
        val action = intent.intentAction

        if (action.isNullOrBlank()) {
            // If the target app declares the Vox contract (e.g. Vox Notes), hand it the command via
            // a fire-and-forget NATIVE intent so it receives the query — otherwise just launch it.
            // Loose, opt-in coupling: constants are local (no shared library).
            if (!query.isNullOrBlank() && fireVoxCommand(context, pkg, intent, query)) return true
            return launchApp(context, pkg)
        }

        // Try the specified intent action
        return when (action) {
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> playFromSearch(context, pkg, query, intent.mediaType)
            Intent.ACTION_VIEW -> {
                if (!query.isNullOrBlank() && AudioPlaybackHelpers.pipedPlayDirect(context, pkg, query)) return true
                viewSearch(context, pkg, resolvedApp, query, intent.uriTemplate)
            }
            Intent.ACTION_WEB_SEARCH -> webSearch(context, pkg, query)
            Intent.ACTION_SEARCH -> {
                if (!query.isNullOrBlank() && AudioPlaybackHelpers.pipedPlayDirect(context, pkg, query)) return true
                browserSearch(context, pkg, resolvedApp, query)
            }
            else -> {
                if (!query.isNullOrBlank() && AudioPlaybackHelpers.pipedPlayDirect(context, pkg, query)) return true
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
    private fun playFromSearch(context: Context, pkg: String, query: String?, mediaType: String? = null): Boolean {
        if (query.isNullOrBlank()) {
            return launchApp(context, pkg)
        }

        // 0. Try Piped API direct play first (works for any app that handles youtu.be URLs)
        if (AudioPlaybackHelpers.pipedPlayDirect(context, pkg, query)) return true

        // 1. Try a loaded declarative API integration (e.g. Spotify's Web API via its OAuth token)
        if (AudioPlaybackHelpers.tryApiIntegrationPlaySearch(context, pkg, query, mediaType)) return true

        // 2. Try intent with EXTRA_MEDIA_FOCUS (artist)
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
            putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        Logger.log("Sending playFromSearch intent with EXTRA_MEDIA_FOCUS=artist for $pkg, query=$query", TAG)
        if (IntentUtils.tryLaunch(context, playIntent, TAG)) {
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
        if (IntentUtils.tryLaunch(context, plainIntent, TAG)) return true

        // 4. Last resort: just launch the app
        return launchApp(context, pkg)
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
                putExtra(Intent.EXTRA_REFERRER, "android-app://com.voxapps.commander")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (IntentUtils.tryLaunch(context, intent, TAG)) return true
        }

        // 2. Try ACTION_WEB_SEARCH with the target package
        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage(pkg)
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (IntentUtils.tryLaunch(context, webSearchIntent, TAG)) return true

        // 3. Fallback: Google search URL via ACTION_VIEW with target package
        val googleUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(googleUrl)
            setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (IntentUtils.tryLaunch(context, viewIntent, TAG)) return true

        // 4. Fallback: Google search URL without package (system default browser)
        val implicitIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(googleUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (IntentUtils.tryLaunch(context, implicitIntent, TAG)) return true

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
                putExtra(Intent.EXTRA_REFERRER, "android-app://com.voxapps.commander")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (IntentUtils.tryLaunch(context, intent, TAG)) return true
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
        if (IntentUtils.tryLaunch(context, searchIntent, TAG)) return true

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
        return IntentUtils.tryLaunch(context, intent, TAG)
    }

    /**
     * If [pkg] declares the Vox intent contract, fire the HANDLE intent with the query (+domain/action).
     * Returns false if the app is not Vox-capable, so the caller falls back to a plain launch.
     */
    private fun fireVoxCommand(context: Context, pkg: String, intent: NluIntent, query: String): Boolean {
        val probe = Intent(VOX_ACTION).apply { setPackage(pkg) }
        if (context.packageManager.queryIntentActivities(probe, 0).isEmpty()) return false
        val voxIntent = Intent(VOX_ACTION).apply {
            setPackage(pkg)
            addCategory(VOX_CATEGORY)
            putExtra(VOX_EXTRA_QUERY, query)
            putExtra(VOX_EXTRA_DOMAIN, intent.domain)
            putExtra(VOX_EXTRA_ACTION, intent.action)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        Logger.log("Firing Vox command to $pkg (domain=${intent.domain}, query=$query)", TAG)
        return IntentUtils.tryLaunch(context, voxIntent, TAG)
    }

    private fun launchApp(context: Context, pkg: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return IntentUtils.tryLaunch(context, launchIntent, TAG)
        }
        Logger.log("GenericLaunchHandler: no launch intent for $pkg", TAG)
        return false
    }

    companion object {
        private const val TAG = "GenericLaunchHandler"

        // Vox intent contract — declared LOCALLY (no shared library); satellites define the same
        // strings on their side. Any app that declares this filter can receive Vox commands.
        private const val VOX_ACTION = "com.voxapps.action.HANDLE"
        private const val VOX_CATEGORY = "com.voxapps.category.VOX"
        private const val VOX_EXTRA_QUERY = "com.voxapps.extra.QUERY"
        private const val VOX_EXTRA_DOMAIN = "com.voxapps.extra.DOMAIN"
        private const val VOX_EXTRA_ACTION = "com.voxapps.extra.ACTION"
    }
}
