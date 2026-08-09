package com.voxapps.commander.domain.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Base64
import com.voxapps.commander.service.MediaSessionListenerService
import com.voxapps.ipc.VoxResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream

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
            .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
            .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST))
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        if (duration > 0) json.put("durationMillis", duration)
        val position = playbackState?.position ?: -1L
        if (position >= 0) json.put("positionMillis", position)
        albumArtBase64(metadata)?.let { json.put("albumArtBase64", it) }
        return VoxResult(ok = true, text = json.toString())
    }

    /** Downscaled + JPEG-compressed so a 15s-polled album art thumbnail doesn't balloon the
     *  response — [ALBUM_ART_MAX_DIMENSION] keeps it small enough for a card-sized thumbnail on
     *  the desktop, not a full-resolution copy. Apps populate either key inconsistently, so both
     *  are tried before giving up (`null` — the desktop just shows no artwork, not an error). */
    private fun albumArtBase64(metadata: MediaMetadata?): String? {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: return null
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        val scaled = if (largestDimension > ALBUM_ART_MAX_DIMENSION) {
            val scale = ALBUM_ART_MAX_DIMENSION.toFloat() / largestDimension
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, ALBUM_ART_JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private inline fun transportResult(context: Context, action: (android.media.session.MediaController) -> Unit): VoxResult {
        val controller = MediaSessionListenerService.getActiveMediaController(context)
            ?: return VoxResult(ok = false, text = "no active media session")
        action(controller)
        return VoxResult(ok = true, text = "ok")
    }

    private const val ALBUM_ART_MAX_DIMENSION = 300
    private const val ALBUM_ART_JPEG_QUALITY = 80
}
