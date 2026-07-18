package com.voxapps.commander.domain.intent.handler

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.net.Uri
import android.view.KeyEvent
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.service.MediaSessionListenerService
import com.voxapps.commander.utils.IntentUtils
import com.voxapps.commander.utils.Logger

/**
 * Handles audio domain intents: play, pause, next, prev.
 * Uses URI templates from AppEntry for generic search-based playback.
 * Supports any app registered in AppRegistry (Spotify, YouTube, LibreTube, VLC, etc.).
 */
class AudioIntentHandler : IntentHandler {

    override fun canHandle(intent: NluIntent): Boolean {
        return intent.domain == IntentTaxonomy.Domains.AUDIO
    }

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        return when (intent.action) {
            IntentTaxonomy.Actions.PLAY -> play(context, intent, resolvedApp)
            IntentTaxonomy.Actions.PAUSE -> sendMediaKey(context, intent, "pause")
            IntentTaxonomy.Actions.STOP -> sendMediaKey(context, intent, "stop")
            IntentTaxonomy.Actions.NEXT -> sendMediaKey(context, intent, "next")
            IntentTaxonomy.Actions.PREV -> sendMediaKey(context, intent, "prev")
            else -> {
                Logger.log("Unsupported audio action: ${intent.action}", TAG)
                false
            }
        }
    }

    private fun play(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        val searchQuery = intent.logicalSubject

        if (!searchQuery.isNullOrBlank()) {
            return playSearch(context, intent, resolvedApp, searchQuery)
        }

        // No specific track — just launch the app in play mode
        return launchAppPlay(context, intent, resolvedApp)
    }

    /**
     * Generic search-based playback using URI templates.
     * Priority: intent.uriTemplate (from FastMap rule) > resolvedApp.uriTemplates > launch intent.
     */
    private fun playSearch(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?, query: String): Boolean {
        val pkg = resolvedApp?.packageName

        // 1. Try a loaded declarative API integration first (uses its OAuth token for direct
        //    playback via the service's own API — e.g. Spotify's Web API today).
        if (AudioPlaybackHelpers.tryApiIntegrationPlaySearch(context, pkg ?: "", query, intent.mediaType, waitMs = 3000)) {
            return true
        }

        // 1. Try MEDIA_PLAY_FROM_SEARCH (standard Android, plays directly)
        if (pkg != null) {
            val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(pkg)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putExtra(android.app.SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (IntentUtils.tryLaunch(context, playIntent)) return true
        }

        // 2. Try YouTube search (NewPipe Extractor or Piped API) — resolves query to videoId
        if (pkg != null) {
            if (PipedSearchHelper.useNewPipe) {
                try {
                    if (kotlinx.coroutines.runBlocking { NewPipeExtractorHelper.searchAndPlay(context, query, pkg) }) {
                        Logger.log("playSearch via NewPipe Extractor succeeded for $pkg", TAG)
                        return true
                    }
                } catch (e: Exception) {
                    Logger.log("NewPipe search failed: ${e.message}", TAG)
                }
            } else {
                if (AudioPlaybackHelpers.pipedPlayDirect(context, pkg, query)) {
                    Logger.log("playSearch via Piped API succeeded for $pkg", TAG)
                    return true
                }
            }
        }

        // 3. Use URI template: intent.uriTemplate first, then resolvedApp.uriTemplates
        val searchTemplate = intent.uriTemplate ?: resolvedApp?.uriTemplates?.get(AppRegistry.TemplateActions.SEARCH)
        if (searchTemplate != null) {
            val uri = searchTemplate.replace(AppRegistry.TemplateParams.QUERY, Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(uri)
                if (pkg != null) setPackage(pkg)
                putExtra(Intent.EXTRA_REFERRER, "android-app://com.voxapps.commander")
            }
            if (IntentUtils.tryLaunch(context, intent)) return true
        }

        // Last resort: try launching the app directly
        if (pkg != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (IntentUtils.tryLaunch(context, launchIntent)) return true
            }
        }

        // Fallback: open market search
        return IntentUtils.tryLaunch(context, Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://search?q=${Uri.encode(query)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    /**
     * No search query — just send a play transport control.
     * Uses sendMediaKey which targets the active media session first.
     */
    private fun launchAppPlay(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        val pkg = resolvedApp?.packageName

        // Try sending media key to active session first
        val sent = sendMediaKey(context, intent, "play")
        if (sent) {
            // Check if there was actually an active session — if sendMediaKey fell through to
            // broadcast/AudioManager, we should also launch the app
            val hasActiveSession = MediaSessionListenerService.getActiveMediaController(context) != null
            if (hasActiveSession) return true
        }

        // No active session — launch the app directly
        if (pkg != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                Logger.log("No active session — launching $pkg directly", TAG)
                return IntentUtils.tryLaunch(context, launchIntent)
            }
        }

        return sent
    }

    /**
     * Sends a media key event (play/pause/next/prev).
     * Uses mediaControlType from intent parameters to decide the method:
     *   - "active_session" (default): Simulate media button — send to the active media session via MediaSessionManager
     *     (whichever app was last playing, e.g. LibreTube if user just paused it).
     *   - "default_app": Send to the resolved app's session/broadcast.
     *   - "audio_button": Simulate audio button — AudioManager.dispatchMediaKeyEvent (global, no session needed).
     */
    private fun sendMediaKey(context: Context, intent: NluIntent, action: String): Boolean {
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return false
        }

        val mediaControlType = intent.mediaControlType ?: "active_session"
        val explicitPkg = intent.targetApp?.takeIf { it.isNotBlank() }

        // audio_button: skip all session logic, go straight to AudioManager
        if (mediaControlType == "audio_button") {
            return dispatchAudioKey(context, keyCode, action)
        }

        // default_app: target the explicitly set app
        if (mediaControlType == "default_app" && explicitPkg != null) {
            val controller = MediaSessionListenerService.getMediaController(context, explicitPkg)
            if (controller != null && sendTransportAction(controller, action)) {
                Logger.log("Sent transport $action to default app session: ${controller.packageName}", TAG)
                return true
            }
            return sendMediaBroadcast(context, explicitPkg, keyCode, action)
        }

        // active_session (default): use the top active media session
        val controller = MediaSessionListenerService.getActiveMediaController(context)
        if (controller != null && sendTransportAction(controller, action)) {
            Logger.log("Sent transport $action to active session: ${controller.packageName}", TAG)
            return true
        }

        // Fallback: broadcast to specific package if set
        if (explicitPkg != null) {
            return sendMediaBroadcast(context, explicitPkg, keyCode, action)
        }

        // Final fallback: AudioManager
        return dispatchAudioKey(context, keyCode, action)
    }

    private fun sendTransportAction(controller: android.media.session.MediaController, action: String): Boolean {
        val transportControls = controller.transportControls
        return when (action) {
            "play" -> { transportControls.play(); true }
            "pause" -> { transportControls.pause(); true }
            "stop" -> { transportControls.stop(); true }
            "next" -> { transportControls.skipToNext(); true }
            "prev" -> { transportControls.skipToPrevious(); true }
            else -> false
        }
    }

    private fun sendMediaBroadcast(context: Context, pkg: String, keyCode: Int, action: String): Boolean {
        return try {
            // For pause/stop, only send ACTION_DOWN — ACTION_UP can be interpreted as resume by some apps (Spotify)
            val needsKeyUp = action != "pause" && action != "stop"
            val intent = Intent("com.android.intent.action.MEDIA_BUTTON").apply {
                setPackage(pkg)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.sendBroadcast(intent)
            if (needsKeyUp) {
                intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
                context.sendBroadcast(intent)
            }
            Logger.log("Sent media key $action to $pkg via broadcast (keyUp=$needsKeyUp)", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to send media key to $pkg: ${e.message}", TAG)
            false
        }
    }

    private fun dispatchAudioKey(context: Context, keyCode: Int, action: String): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            // For pause/stop, only send ACTION_DOWN — ACTION_UP can be interpreted as resume by some apps (Spotify)
            val needsKeyUp = action != "pause" && action != "stop"
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            if (needsKeyUp) {
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            Logger.log("Sent media key $action via AudioManager (global, keyUp=$needsKeyUp)", TAG)
            true
        } catch (e: Exception) {
            Logger.log("Failed to dispatch media key: ${e.message}", TAG)
            false
        }
    }

    companion object {
        private const val TAG = "AudioIntentHandler"
    }
}
