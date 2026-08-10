package com.whispercpp.whisper

import android.content.res.AssetManager
import com.voxapps.logging.Logger
import kotlinx.coroutines.*
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

/**
 * HIGH-LEVEL CONTEXT: Manages Whisper engine lifecycle and thread-safe transcription.
 */
class WhisperContext private constructor(@Volatile private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope: CoroutineScope = CoroutineScope(executor.asCoroutineDispatcher())

    suspend fun transcribeData(data: FloatArray, threads: Int, language: String? = null, printTimestamp: Boolean = true): String = withContext(scope.coroutineContext) {
        if (ptr == 0L) return@withContext "Error: Context released"
        
        Logger.log("Transcribing with $threads threads, lang: ${language ?: "auto"}", LOG_TAG)
        WhisperLib.fullTranscribe(ptr, threads, data, language)
        
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return@withContext buildString {
            for (i in 0 until textCount) {
                if (printTimestamp) {
                    val t0 = WhisperLib.getTextSegmentT0(ptr, i)
                    val t1 = WhisperLib.getTextSegmentT1(ptr, i)
                    val textTimestamp = "[${toTimestamp(t0)} --> ${toTimestamp(t1)}]"
                    val textSegment = WhisperLib.getTextSegment(ptr, i)
                    append("$textTimestamp: $textSegment\n")
                } else {
                    append(WhisperLib.getTextSegment(ptr, i))
                }
            }
        }.trim()
    }

    fun release() {
        // Idempotent: a second call (e.g. finalize() after an explicit release) must
        // not submit to the already-shut-down executor (RejectedExecutionException).
        if (ptr == 0L) return
        // Serialize with transcribeData through the single-thread executor:
        // freeContext must never run concurrently with fullTranscribe (native crash).
        runBlocking(scope.coroutineContext) {
            if (ptr != 0L) {
                WhisperLib.freeContext(ptr)
                ptr = 0
            }
        }
        // Shut down the executor so its worker thread doesn't leak once the native
        // context is gone (the context is recreated on the next transcription).
        executor.shutdown()
    }

    protected fun finalize() {
        release()
    }

    companion object {
        fun createContextFromFile(filePath: String, useGpu: Boolean): WhisperContext {
            val ptr = WhisperLib.initContext(filePath, useGpu)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath (GPU=$useGpu)")
            }
            return WhisperContext(ptr)
        }
    }
}

/**
 * JNI BRIDGE: Low-level access to the compiled C++ code.
 */
class WhisperLib {
    companion object {
        private var isLoaded = false

        /**
         * Attempts to load Whisper native libraries.
         * First tries system-installed libs (System.loadLibrary).
         * If that fails (e.g. libs excluded from APK), tries loading from [libDir] via System.load().
         * @param libDir Absolute path to directory containing .so files (e.g. filesDir/whisper_libs)
         * @return true if libraries loaded successfully
         */
        fun load(libDir: String? = null): Boolean {
            if (isLoaded) return true
            return try {
                Logger.log("Loading native libraries (libwhisper.so containing JNI)...", LOG_TAG)

                // Try system-installed libraries first
                try {
                    System.loadLibrary("omp")
                    System.loadLibrary("ggml-base")
                    System.loadLibrary("ggml-cpu")
                    System.loadLibrary("ggml-vulkan")
                    System.loadLibrary("ggml")
                    System.loadLibrary("whisper")
                    isLoaded = true
                    Logger.log("Libraries loaded from system", LOG_TAG)
                    return true
                } catch (e: UnsatisfiedLinkError) {
                    Logger.log("System libs not found, trying libDir: $libDir", LOG_TAG)
                }

                // Fallback: load from filesDir (downloaded DLC libs)
                if (libDir == null) {
                    Logger.log("No libDir provided, cannot load Whisper libs", LOG_TAG)
                    return false
                }

                // Load order matters: dependencies must be loaded before dependents
                val libs = listOf("libomp.so", "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so", "libggml.so", "libwhisper.so")
                for (lib in libs) {
                    val path = java.io.File(libDir, lib)
                    if (!path.exists()) {
                        Logger.log("Missing lib: $path", LOG_TAG)
                        return false
                    }
                    Logger.log("Loading $path", LOG_TAG)
                    // Read-only before loading. Android warns on every load of a writable file
                    // ("This will throw on a future Android version") — executable code a process
                    // can still rewrite is the pattern being closed off, and these libraries are
                    // downloaded, so they land writable. Verified on API 36: this clears the
                    // warning, and the libraries still load. Same treatment as the DLC libs in
                    // core:nativelibs.
                    path.setReadOnly()
                    System.load(path.absolutePath)
                }
                isLoaded = true
                Logger.log("Libraries loaded from libDir: $libDir", LOG_TAG)
                true
            } catch (e: UnsatisfiedLinkError) {
                Logger.log("Native load failed: ${e.message}", LOG_TAG)
                false
            }
        }

        fun isReady(): Boolean = isLoaded

        /**
         * Proactively probes the device for real Vulkan support by creating a Vulkan
         * instance and enumerating physical devices natively. Returns false on any
         * device that lacks a usable Vulkan-capable GPU. Safe to call off the main thread.
         */
        fun isVulkanSupported(): Boolean {
            if (!load()) return false
            return try {
                isVulkanAvailable()
            } catch (e: Throwable) {
                Logger.log("Vulkan probe threw: ${e.message}", LOG_TAG)
                false
            }
        }

        /**
         * Runs a real GPU compute workload (small matmul) through the ggml-vulkan
         * backend - the same path whisper inference uses. Returns false if the GPU
         * produces wrong/non-finite results. WARNING: on a broken GPU this can crash
         * the *process* natively (uncatchable), so call it only inside an isolated process.
         */
        fun isVulkanWorkloadSupported(): Boolean {
            if (!load()) return false
            return try {
                runVulkanSelfTest()
            } catch (e: Throwable) {
                Logger.log("Vulkan self-test threw: ${e.message}", LOG_TAG)
                false
            }
        }

        // JNI Methods - Signature matching libwhisper.so (contains JNI Patch)
        external fun isVulkanAvailable(): Boolean
        external fun runVulkanSelfTest(): Boolean
        external fun initContext(modelPath: String, useGpu: Boolean): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String?)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
    }
}

private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr.toInt(), min.toInt(), sec.toInt(), delimiter, msec.toInt())
}
