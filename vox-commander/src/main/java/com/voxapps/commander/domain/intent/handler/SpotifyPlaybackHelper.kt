package com.voxapps.commander.domain.intent.handler

import android.content.Context
import com.voxapps.commander.service.SpotifyPkceManager
import com.voxapps.commander.service.SpotifyRemoteManager
import com.voxapps.commander.service.SpotifyWebApi
import com.voxapps.commander.utils.Logger

object SpotifyPlaybackHelper {

    private const val TAG = "SpotifyPlaybackHelper"

    fun tryPlaySearch(context: Context, pkg: String, query: String, waitMs: Long = 0): Boolean {
        if (pkg != com.voxapps.commander.utils.PackageNames.SPOTIFY || !SpotifyPkceManager.isAuthorized) return false

        val clientId = SpotifyRemoteManager.getClientId() ?: return false

        if (waitMs > 0) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                try { context.startActivity(launchIntent) } catch (_: Exception) {}
                Thread.sleep(waitMs)
            }
        }

        if (SpotifyWebApi.playSearch(clientId, query)) {
            Logger.log("playSearch via Spotify Web API succeeded", TAG)
            return true
        }

        Logger.log("Spotify Web API failed, falling back to intent", TAG)
        return false
    }

    fun pipedPlayDirect(context: Context, pkg: String?, query: String): Boolean {
        return try {
            kotlinx.coroutines.runBlocking {
                PipedSearchHelper.searchAndPlay(context, query, pkg)
            }
        } catch (e: Exception) {
            Logger.log("Piped play direct failed: ${e.message}", TAG)
            false
        }
    }
}
