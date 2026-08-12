package com.voxapps.llamacpp

import com.voxapps.logging.Logger
import java.io.File

/**
 * Loader for the llama.cpp runtime. One library, statically self-contained: libllama.so carries
 * its own ggml (CPU backend, no OpenMP), so there is no dependency ordering to get wrong — the
 * list exists because the suite pins it against the build script, the publish script and the
 * release gate, same shape as whisper's.
 */
object LibLlama {

    private const val LOG_TAG = "LibLlama"

    /** Load order; must stay in step with `llamaLibs` in build.gradle.kts, `LIBS` in
     *  scripts/publish_llama_libs.sh and scripts/check_llama_published.sh. */
    val LLAMA_LIBS = listOf("libllama.so")

    @Volatile
    private var isLoaded = false

    fun load(libDir: File?): Boolean {
        if (isLoaded) return true
        return try {
            // System-installed first (a bundled build packages the lib in nativeLibraryDir).
            try {
                System.loadLibrary("llama")
                isLoaded = true
                Logger.log("libllama loaded from system", LOG_TAG)
                return true
            } catch (e: UnsatisfiedLinkError) {
                Logger.log("System libllama not found, trying libDir: $libDir", LOG_TAG)
            }

            if (libDir == null) {
                Logger.log("No libDir provided, cannot load llama libs", LOG_TAG)
                return false
            }

            for (lib in LLAMA_LIBS) {
                val path = File(libDir, lib)
                if (!path.exists()) {
                    Logger.log("Missing lib: $path", LOG_TAG)
                    return false
                }
                // Read-only before loading — downloaded code lands writable and Android warns on
                // every load of a writable file. Same treatment as whisper's and the DLC libs'.
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
}
