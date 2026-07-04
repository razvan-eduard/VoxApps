package com.voxcommander.app.utils

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Helper for requesting/abandoning audio focus with automatic API-level fallback.
 */
object AudioFocusHelper {

    /**
     * Requests audio focus. Returns the created AudioFocusRequest (for later abandonment),
     * or null on legacy API / failure.
     */
    fun requestFocus(
        audioManager: AudioManager,
        focusType: Int,
        usage: Int = AudioAttributes.USAGE_ASSISTANT,
        contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH,
        onFocusChange: ((Int) -> Unit)? = null
    ): AudioFocusRequest? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = AudioFocusRequest.Builder(focusType)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
            if (onFocusChange != null) {
                builder.setOnAudioFocusChangeListener(onFocusChange)
            }
            val request = builder.build()
            audioManager.requestAudioFocus(request)
            return request
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, focusType)
            return null
        }
    }

    /**
     * Abandons a previously requested audio focus.
     */
    fun abandonFocus(audioManager: AudioManager, request: AudioFocusRequest?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
