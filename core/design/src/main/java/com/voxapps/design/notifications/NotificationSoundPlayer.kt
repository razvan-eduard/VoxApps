package com.voxapps.design.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object NotificationSoundPlayer {
    private var activeMediaPlayer: MediaPlayer? = null

    fun play(context: Context, soundUri: String?, volume: Int, length: String, vibrationEnabled: Boolean, soundOnly: Boolean = false) {
        // Stop any current preview playback
        activeMediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        activeMediaPlayer = null

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
            activeMediaPlayer = mediaPlayer

            val repeatCount = when (length) {
                "MEDIUM" -> 2
                "LONG" -> 3
                else -> 1
            }

            var currentPlay = 0
            mediaPlayer.setOnCompletionListener {
                currentPlay++
                if (currentPlay < repeatCount) {
                    it.start()
                } else {
                    if (activeMediaPlayer == it) activeMediaPlayer = null
                    it.release()
                }
            }
            mediaPlayer.start()
        } catch (e: Exception) {
            e.printStackTrace()
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

            val pattern = when (length) {
                "SHORT" -> longArrayOf(0, 200)
                "MEDIUM" -> longArrayOf(0, 300, 200, 300)
                "LONG" -> longArrayOf(0, 400, 200, 400, 200, 400)
                else -> longArrayOf(0, 200)
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
        }
    }
}
