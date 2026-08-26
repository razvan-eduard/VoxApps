package com.voxapps.design.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.voxapps.logging.Logger

private const val TAG = "NotificationSoundPlayer"

object NotificationSoundPlayer {
    private var activeMediaPlayer: MediaPlayer? = null

    /** Number of times the sound repeats for each length setting — pulled out as a pure function
     *  so it's unit-testable without a real MediaPlayer/Context. */
    internal fun repeatCountFor(length: String): Int = when (length) {
        "MEDIUM" -> 2
        "LONG" -> 3
        else -> 1
    }

    /** Vibration waveform (on/off durations in ms, starting with an initial delay of 0) for each
     *  length setting — same testability reasoning as [repeatCountFor]. */
    internal fun vibrationPatternFor(length: String): LongArray = when (length) {
        "SHORT" -> longArrayOf(0, 200)
        "MEDIUM" -> longArrayOf(0, 300, 200, 300)
        "LONG" -> longArrayOf(0, 400, 200, 400, 200, 400)
        else -> longArrayOf(0, 200)
    }

    fun play(context: Context, soundUri: String?, volume: Int, length: String, vibrationEnabled: Boolean, soundOnly: Boolean = false) {
        // Stop any current preview playback. Taken under the lock so exactly one caller — this
        // play() or the finished player's own completion listener — releases any given instance.
        val previous = synchronized(this) {
            val p = activeMediaPlayer
            activeMediaPlayer = null
            p
        }
        previous?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }

        // 1. Play Sound
        val uri = soundUri?.let { Uri.parse(it) } ?: Uri.parse("content://settings/system/notification_sound")
        
        try {
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM) // Bypass notification stream volume
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(volume / 100f, volume / 100f)
                prepare()
            }
            synchronized(this) { activeMediaPlayer = mediaPlayer }

            val repeatCount = repeatCountFor(length)

            var currentPlay = 0
            mediaPlayer.setOnCompletionListener {
                currentPlay++
                if (currentPlay < repeatCount) {
                    // A newer play() may have taken and released this instance between repeats.
                    runCatching { it.start() }
                } else {
                    val stillMine = synchronized(this) {
                        (activeMediaPlayer == it).also { mine -> if (mine) activeMediaPlayer = null }
                    }
                    if (stillMine) it.release()
                }
            }
            mediaPlayer.start()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to play notification sound (uri=$uri)", e)
        }

        // 2. Vibrate
        if (vibrationEnabled && !soundOnly) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = vibrationPatternFor(length)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
        }
    }
}
