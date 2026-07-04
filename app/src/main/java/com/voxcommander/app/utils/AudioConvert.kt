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

    fun calculateFilteredRms(filtered: FloatArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            sum += filtered[i].toDouble() * filtered[i]
        }
        return kotlin.math.sqrt(sum / length).toFloat()
    }

    fun calculateRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i]
            sum += sample.toDouble() * sample
        }
        return kotlin.math.sqrt(sum / length).toFloat() / 32768.0f
    }
}
