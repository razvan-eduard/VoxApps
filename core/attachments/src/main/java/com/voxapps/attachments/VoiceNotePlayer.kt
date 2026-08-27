package com.voxapps.attachments

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback for recorded voice notes: one [MediaPlayer], one file playing at a time. Tapping a
 * playing note stops it; tapping another switches to it. [playingFileName] is what every inline
 * mini-player watches to draw its own play/stop state.
 *
 * Ownership follows [com.voxapps.design.notifications.NotificationSoundPlayer]'s discipline:
 * exactly one caller — the [toggle] that took the instance under the lock, or the completion
 * listener that still owns it — ever releases any given player, so a fast tap-tap-tap can never
 * stop or release the same instance twice.
 */
object VoiceNotePlayer {

    private var activePlayer: MediaPlayer? = null
    private var activeFileName: String? = null

    private val _playingFileName = MutableStateFlow<String?>(null)
    val playingFileName: StateFlow<String?> = _playingFileName

    /**
     * Starts [file] (stopping whatever was playing), or stops it if it is the one playing.
     * Returns true when the file is now playing, false when it was stopped or failed to start.
     */
    fun toggle(context: Context, file: File): Boolean {
        val previous = synchronized(this) {
            val p = activePlayer
            val wasThisFile = activeFileName == file.name
            activePlayer = null
            activeFileName = null
            _playingFileName.value = null
            if (wasThisFile) {
                p?.let {
                    runCatching { if (it.isPlaying) it.stop() }
                    runCatching { it.release() }
                }
                return false
            }
            p
        }
        previous?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }

        if (!file.exists()) return false
        return try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                prepare()
            }
            player.setOnCompletionListener {
                val stillMine = synchronized(this) {
                    (activePlayer == it).also { mine ->
                        if (mine) {
                            activePlayer = null
                            activeFileName = null
                            _playingFileName.value = null
                        }
                    }
                }
                if (stillMine) it.release()
            }
            synchronized(this) {
                activePlayer = player
                activeFileName = file.name
                _playingFileName.value = file.name
            }
            player.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Stops and releases whatever is playing — call from the hosting activity's onPause. */
    fun stop() {
        val previous = synchronized(this) {
            val p = activePlayer
            activePlayer = null
            activeFileName = null
            _playingFileName.value = null
            p
        }
        previous?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
    }
}
