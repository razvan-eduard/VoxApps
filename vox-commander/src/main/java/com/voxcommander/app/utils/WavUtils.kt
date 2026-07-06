package com.voxcommander.app.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility to wrap raw PCM data into a valid WAV (RIFF) container.
 * OpenAI Whisper API requires a valid audio format header.
 */
object WavUtils {

    fun wrapPcmToWav(pcmData: ByteArray, sampleRate: Int = 16000): ByteArray {
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize
        
        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            
            // RIFF chunk descriptor
            put("RIFF".toByteArray())
            putInt(chunkSize)
            put("WAVE".toByteArray())
            
            // fmt sub-chunk
            put("fmt ".toByteArray())
            putInt(16) // Sub-chunk size (16 for PCM)
            putShort(1.toShort()) // Audio format (1 for PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            
            // data sub-chunk
            put("data".toByteArray())
            putInt(dataSize)
        }
        
        val wavData = ByteArray(44 + dataSize)
        System.arraycopy(header.array(), 0, wavData, 0, 44)
        System.arraycopy(pcmData, 0, wavData, 44, dataSize)
        
        return wavData
    }
}
