package com.voxcommander.app.domain.diagnostic

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.voxcommander.app.utils.Logger
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperLib
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Runs a full Whisper GPU inference test in an ISOLATED process (declared via android:process
 * in the manifest). This validates the entire GPU pipeline (attention, layernorm, etc.)
 * exactly as used in real transcription. If the GPU workload crashes the process natively,
 * only this process dies; the client detects the disconnect and marks Vulkan incompatible,
 * keeping the main app crash-free.
 */
class VulkanProbeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(IncomingHandler())
    private var modelPath: String? = null

    override fun onBind(intent: Intent?): IBinder {
        modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH)
        return messenger.binder
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_RUN_TEST -> {
                    val reply = msg.replyTo
                    thread(name = "vulkan-inference-test") {
                        // May crash this (isolated) process natively on broken GPUs.
                        val ok = runFullInferenceTest()
                        sendResult(reply, ok)
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private fun runFullInferenceTest(): Boolean {
        val path = modelPath
        if (path == null || !File(path).exists()) {
            Logger.log("Model path invalid or not found: $path", TAG)
            return false
        }

        try {
            Logger.log("Loading native libraries...", TAG)
            val libDir = File(filesDir, "whisper_libs").absolutePath
            WhisperLib.load(libDir)

            Logger.log("Loading Whisper model with GPU for inference test...", TAG)
            val ctx = WhisperContext.createContextFromFile(path, useGpu = true)
            if (ctx == null) {
                Logger.log("Failed to create Whisper context with GPU", TAG)
                return false
            }

            // Generate 1 second of dummy audio (silence) - enough to trigger GPU ops
            val sampleRate = 16000
            val durationSec = 1
            val audioData = FloatArray(sampleRate * durationSec) { 0f }

            Logger.log("Running inference on dummy audio...", TAG)
            val result = runBlocking {
                ctx.transcribeData(audioData, threads = 1, language = null, printTimestamp = false)
            }

            ctx.release()
            Logger.log("Inference test completed successfully. Result: $result", TAG)
            return true
        } catch (e: Throwable) {
            Logger.log("Inference test failed: ${e.message}", TAG)
            return false
        }
    }

    private fun sendResult(reply: Messenger?, ok: Boolean) {
        try {
            val m = Message.obtain(null, MSG_RESULT)
            m.arg1 = if (ok) 1 else 0
            reply?.send(m)
            Logger.log("Self-test finished in isolated process: ok=$ok", TAG)
        } catch (e: RemoteException) {
            Logger.log("Failed to send self-test result: ${e.message}", TAG)
        }
        handler.post { stopSelf() }
    }

    companion object {
        const val TAG = "VulkanProbeService"
        const val MSG_RUN_TEST = 1
        const val MSG_RESULT = 2
        const val EXTRA_MODEL_PATH = "model_path"
    }
}
