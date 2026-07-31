package com.voxapps.commander.domain.media

import android.content.Context
import android.media.session.PlaybackState
import com.voxapps.commander.service.MediaSessionListenerService
import com.voxapps.ipc.VoxResult
import org.json.JSONObject

/**
 * Handles [com.voxapps.ipc.VoxIpc.OP_MEDIA_CONTROL] for [com.voxapps.commander.receiver.VoxCommandReceiver] —
 * the network-facing counterpart of the VoxConnect Bridge (hosted in Vox Hub) relays media commands
 * here rather than touching [MediaSessionListenerService] itself, since Commander is the only app
 * holding the notification-listener permission grant that media-session access requires. Deliberately
 * separate from [com.voxapps.commander.domain.intent.handler.AudioIntentHandler] — that class's
 * transport-control helpers are private and voice-intent-specific; this is a small, independent path
 * straight onto [MediaSessionListenerService]'s already-public API.
 */
object MediaControlIpcHandler {

    /** Synchronous — every branch is a plain, non-suspending call, safe to invoke directly from
     *  `onReceive()` (no `goAsync()` needed, mirrors the existing `ping` branch). */
    fun handle(context: Context, mediaAction: String?): VoxResult {
        if (!MediaSessionListenerService.isPermissionGranted(context)) {
            return VoxResult(ok = false, text = "notification access not granted")
        }
        return when (mediaAction) {
            "status" -> statusResult(context)
            "play" -> transportResult(context) { it.transportControls.play() }
            "pause" -> transportResult(context) { it.transportControls.pause() }
            "next" -> transportResult(context) { it.transportControls.skipToNext() }
            "prev" -> transportResult(context) { it.transportControls.skipToPrevious() }
            else -> VoxResult(ok = false, text = "unknown mediaAction: $mediaAction")
        }
    }

    private fun statusResult(context: Context): VoxResult {
        val controller = MediaSessionListenerService.getActiveMediaController(context)
            ?: return VoxResult(ok = true, text = JSONObject().put("playing", false).toString())
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val json = JSONObject()
            .put("playing", playbackState?.state == PlaybackState.STATE_PLAYING)
            .put("packageName", controller.packageName)
            .put("title", metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
            .put("artist", metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
        return VoxResult(ok = true, text = json.toString())
    }

    private inline fun transportResult(context: Context, action: (android.media.session.MediaController) -> Unit): VoxResult {
        val controller = MediaSessionListenerService.getActiveMediaController(context)
            ?: return VoxResult(ok = false, text = "no active media session")
        action(controller)
        return VoxResult(ok = true, text = "ok")
    }
}
