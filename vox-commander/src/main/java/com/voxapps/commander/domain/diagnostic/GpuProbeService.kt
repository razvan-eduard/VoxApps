package com.voxapps.commander.domain.diagnostic

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.logging.Logger
import com.voxapps.llamacpp.LibLlama
import com.voxapps.llamacpp.LlamaBridgeImpl
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperLib
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Runs a real GPU inference for one engine in an ISOLATED process (declared via android:process
 * in the manifest) — whisper transcribes a second of silence, llama decodes a grammar sentinel —
 * validating the entire GPU pipeline exactly as production uses it. If the GPU workload crashes
 * the process natively, only this process dies; the client observes the disconnect and
 * attributes it, keeping the main app crash-free. Which engine is probed rides in
 * [EXTRA_ENGINE] ([SettingsRepository.GPU_WHISPER] / [SettingsRepository.GPU_LLAMA]).
 */
class GpuProbeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(IncomingHandler())
    private var modelPath: String? = null
    private var engine: String = SettingsRepository.GPU_WHISPER

    override fun onBind(intent: Intent?): IBinder {
        modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH)
        engine = intent?.getStringExtra(EXTRA_ENGINE) ?: SettingsRepository.GPU_WHISPER
        return messenger.binder
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_RUN_TEST -> {
                    val reply = msg.replyTo
                    thread(name = "gpu-inference-test") {
                        // May crash this (isolated) process natively on broken GPUs.
                        val code = if (engine == SettingsRepository.GPU_LLAMA) {
                            runLlamaInferenceTest()
                        } else {
                            if (runFullInferenceTest()) RESULT_OK else RESULT_FAILED
                        }
                        sendResult(reply, code)
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
            // No repair or refetch here: this runs in an isolated process whose whole job is to
            // answer whether the installed libraries work on this GPU. A load failure is a verdict.
            val libDir = com.voxapps.commander.data.remote.WhisperEngineManager.libDir(this).absolutePath
            if (!WhisperLib.load(libDir)) {
                Logger.log("Native libraries failed to load — cannot probe", TAG)
                return false
            }

            Logger.log("Loading Whisper model with GPU for inference test...", TAG)
            val ctx = WhisperContext.createContextFromFile(path, useGpu = true)
            if (ctx == null) {
                Logger.log("Failed to create Whisper context with GPU", TAG)
                return false
            }

            // Generate 1 second of dummy audio (silence) - enough to trigger GPU ops
            val sampleRate = com.voxapps.audio.VOICE_SAMPLE_RATE_HZ
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

    /**
     * The llama half of the probe: load the tiny test model with every layer on the GPU and
     * decode under a sentinel grammar. Exactly `XOK` back means the whole pipeline — driver,
     * shader compilation, matmul, grammar-constrained sampling — ran on this device's GPU and
     * produced a correct result; a plausible-looking wrong answer fails, same as a crash.
     */
    private fun runLlamaInferenceTest(): Int {
        val path = modelPath
        if (path == null || !File(path).exists()) {
            Logger.log("Llama probe model invalid or not found: $path", TAG)
            return RESULT_FAILED
        }
        return try {
            // Same no-repair contract as the whisper branch: this process answers whether the
            // installed runtime works on this GPU; a load failure is a verdict.
            val libDir = com.voxapps.commander.data.remote.LlamaEngineManager(this).libDir
            if (!LibLlama.load(libDir)) {
                Logger.log("llama native library failed to load — cannot probe", TAG)
                return RESULT_FAILED
            }
            // Asked before the workload, because the workload cannot answer it. A build with no
            // GPU backend, or a device the backend finds no GPU on, runs the whole thing on the
            // CPU and returns the right answer — indistinguishable from success by result alone.
            if (LlamaBridgeImpl.gpuMemory() == null) {
                Logger.log("No GPU device reported — nothing to probe", TAG)
                return RESULT_NO_GPU
            }
            val handle = LlamaBridgeImpl.loadModel(path, nCtx = 512, nThreads = 2, nGpuLayers = -1)
            try {
                val out = LlamaBridgeImpl.complete(
                    handle,
                    systemPrompt = "",
                    userText = "Once upon a time",
                    grammarGbnf = "root ::= \"XOK\"",
                    maxTokens = 8,
                    temperature = 0.1f
                )
                Logger.log("Llama GPU probe output: $out", TAG)
                if (out == "XOK") RESULT_OK else RESULT_FAILED
            } finally {
                LlamaBridgeImpl.freeModel(handle)
            }
        } catch (e: Throwable) {
            Logger.log("Llama GPU probe failed: ${e.message}", TAG)
            RESULT_FAILED
        }
    }

    private fun sendResult(reply: Messenger?, code: Int) {
        try {
            val m = Message.obtain(null, MSG_RESULT)
            m.arg1 = code
            reply?.send(m)
            Logger.log("Self-test finished in isolated process: code=$code", TAG)
        } catch (e: RemoteException) {
            Logger.log("Failed to send self-test result: ${e.message}", TAG)
        }
        handler.post { stopSelf() }
    }

    companion object {
        const val TAG = "GpuProbeService"
        const val MSG_RUN_TEST = 1
        const val MSG_RESULT = 2

        /** A real GPU ran the workload and produced the right answer. */
        const val RESULT_OK = 1
        /** The workload ran but failed, or the process died carrying it. */
        const val RESULT_FAILED = 0
        /**
         * There is no GPU device to test. Distinct from a failure because the workload does not
         * crash and does not answer wrongly — it quietly runs on the CPU and looks like a pass,
         * which would hand out a verified verdict for work no GPU touched.
         */
        const val RESULT_NO_GPU = 2
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_ENGINE = "engine"
    }
}
