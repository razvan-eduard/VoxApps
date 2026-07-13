package com.voxapps.commander.data.remote

import android.content.Context
import com.voxapps.commander.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages "Essential DLC" native libraries that are too large to bundle in the APK
 * for F-Droid/IzzyOnDroid limits (30MB).
 *
 * Downloads libraries to filesDir/native_libs/ and provides manual loading via System.load().
 */
object NativeLibManager {
    private const val TAG = "NativeLibManager"
    private const val LIB_DIR_NAME = "native_libs"

    // Base URL for the native libraries. Pointing to the main repo releases.
    private const val RELEASE_BASE = "https://github.com/razvan-eduard/VoxApps/releases/download/"

    // Libraries stripped from the release APK zip post-build (see
    // scripts/strip_dlc_libs.sh / release-commander.yml) rather than via AGP's
    // packaging.jniLibs.excludes, which proved unreliable on arm64-v8a for this dependency set
    // (see build.gradle.kts's release packaging comment / docs/BUILD_TIME_DEPENDENCIES.md for the
    // investigation) — plain zip removal sidesteps that bug entirely. Order matters:
    // libsherpa-onnx-jni.so's only external NEEDED entry is libonnxruntime.so (confirmed via
    // readelf), so onnxruntime must load first; libllm_inference_engine_jni.so and libvosk.so are
    // self-contained (only system libs), so their position relative to each other doesn't matter.
    val ESSENTIAL_LIBS = listOf(
        "libonnxruntime.so",
        "libllm_inference_engine_jni.so",
        "libvosk.so",
        "libsherpa-onnx-jni.so"
    )

    private fun getReleaseTag(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "commander-v${pInfo.versionName}"
        } catch (e: Exception) {
            "commander-v0.5-beta" // Fallback
        }
    }

    private fun getDownloadUrl(context: Context, libName: String): String {
        return "$RELEASE_BASE${getReleaseTag(context)}/$libName"
    }

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    enum class Status { IDLE, CHECKING, DOWNLOADING, READY, ERROR }

    private fun getLibDir(context: Context): File = File(context.filesDir, LIB_DIR_NAME)

    /**
     * Checks if all essential libraries are already present locally.
     */
    fun areLibsPresent(context: Context): Boolean {
        // If we are in a debug build or any build that HAS them bundled, they will be in nativeLibraryDir
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        val allInSystem = ESSENTIAL_LIBS.all { File(systemDir, it).exists() }
        if (allInSystem) return true

        // Otherwise check our DLC folder
        val libDir = getLibDir(context)
        return ESSENTIAL_LIBS.all { File(libDir, it).exists() }
    }

    /**
     * Initializes and loads the libraries. Call this at app startup (Splash).
     */
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (_status.value == Status.READY) return@withContext
        _status.value = Status.CHECKING

        if (areLibsPresent(context)) {
            loadAll(context)
            _status.value = Status.READY
        } else {
            // Trigger download
            val success = downloadLibs(context)
            if (success) {
                loadAll(context)
                _status.value = Status.READY
            } else {
                _status.value = Status.ERROR
            }
        }
    }

    private suspend fun downloadLibs(context: Context): Boolean = withContext(Dispatchers.IO) {
        _status.value = Status.DOWNLOADING
        val libDir = getLibDir(context)
        if (!libDir.exists()) libDir.mkdirs()

        val client = okhttp3.OkHttpClient()
        var downloaded = 0
        val total = ESSENTIAL_LIBS.size

        for (libName in ESSENTIAL_LIBS) {
            val targetFile = File(libDir, libName)
            if (targetFile.exists()) {
                downloaded++
                _downloadProgress.value = downloaded.toFloat() / total
                continue
            }

            val url = getDownloadUrl(context, libName)
            Logger.log("Downloading essential lib: $libName from $url", TAG)

            try {
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.log("Failed to download $libName: HTTP ${response.code}", TAG)
                        return@withContext false
                    }
                    response.body?.byteStream()?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                downloaded++
                _downloadProgress.value = downloaded.toFloat() / total
            } catch (e: Exception) {
                Logger.log("Error downloading $libName: ${e.message}", TAG)
                targetFile.delete()
                return@withContext false
            }
        }
        true
    }

    /**
     * Manually loads the libraries using System.load() if they aren't in the default path.
     */
    fun loadAll(context: Context) {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        val libDir = getLibDir(context)

        for (libName in ESSENTIAL_LIBS) {
            try {
                val systemFile = File(systemDir, libName)
                if (systemFile.exists()) {
                    // It's bundled in APK (Debug or non-minified build)
                    val libShortName = libName.removePrefix("lib").removeSuffix(".so")
                    System.loadLibrary(libShortName)
                    Logger.log("Loaded $libName from system path", TAG)
                } else {
                    // It's in DLC folder
                    val dlcFile = File(libDir, libName)
                    if (dlcFile.exists()) {
                        System.load(dlcFile.absolutePath)
                        Logger.log("Loaded $libName from DLC path: ${dlcFile.absolutePath}", TAG)
                    } else {
                        Logger.log("CRITICAL: $libName not found in system or DLC!", TAG)
                    }
                }
            } catch (e: Exception) {
                Logger.log("Failed to load $libName: ${e.message}", TAG)
            }
        }
    }
}
