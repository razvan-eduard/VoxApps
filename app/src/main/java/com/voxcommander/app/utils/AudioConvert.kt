package com.voxcommander.app.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioConvert {

    fun pcm16ToFloat(audio: ByteArray): FloatArray {
        val shorts = byteArrayToShorts(audio)
        val floats = FloatArray(shorts.size)
        for (i in shorts.indices) {
            floats[i] = shorts[i] / 32768.0f
        }
        return floats
    }

    fun byteArrayToShorts(audio: ByteArray): ShortArray {
        val shorts = ShortArray(audio.size / 2)
        ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }
}
