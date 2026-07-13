package com.voxapps.vision.data

import android.content.Context
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Manages "Essential DLC" native libraries for VoxVision.
 * Features atomic downloads and integrity checks to prevent crashes.
 */
object NativeLibManager {
    private const val TAG = "NativeLibManager"
    private const val LIB_DIR_NAME = "native_libs"
    private const val RELEASE_BASE = "https://github.com/razvan-eduard/VoxApps/releases/download/"

    val ESSENTIAL_LIBS = listOf(
        "libonnxruntime.so",
        "libopencv_core.so",
        "libopencv_imgproc.so",
        "libopencv_imgcodecs.so",
        "libopencv_java4.so"
    )

    private val _status = MutableStateFlow<Status>(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    enum class Status { IDLE, CHECKING, DOWNLOADING, READY, ERROR }

    private fun getLibDir(context: Context): File = File(context.filesDir, LIB_DIR_NAME)

    private fun getReleaseTag(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "vision-v${pInfo.versionName}"
        } catch (e: Exception) {
            "vision-v0.3" // Current fallback
        }
    }

    /**
     * Verifies that all libs are present AND have non-zero size.
     */
    fun areLibsPresent(context: Context): Boolean {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        if (ESSENTIAL_LIBS.all { File(systemDir, it).exists() }) return true
        
        val libDir = getLibDir(context)
        return ESSENTIAL_LIBS.all { 
            val f = File(libDir, it)
            f.exists() && f.length() > 0 
        }
    }

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (_status.value == Status.READY) return@withContext
        _status.value = Status.CHECKING
        
        if (areLibsPresent(context)) {
            try {
                loadAll(context)
                _status.value = Status.READY
            } catch (e: Throwable) {
                Logger.e(TAG, "Native load failed for existing files: ${e.message}")
                // Cleanup potentially corrupt files and try redownload
                getLibDir(context).deleteRecursively()
                triggerDownload(context)
            }
        } else {
            triggerDownload(context)
        }
    }

    private suspend fun triggerDownload(context: Context) {
        if (downloadLibs(context)) {
            try {
                loadAll(context)
                _status.value = Status.READY
            } catch (e: Throwable) {
                Logger.e(TAG, "Native load failed after download: ${e.message}")
                _status.value = Status.ERROR
            }
        } else {
            _status.value = Status.ERROR
        }
    }

    private suspend fun downloadLibs(context: Context): Boolean = withContext(Dispatchers.IO) {
        _status.value = Status.DOWNLOADING
        val libDir = getLibDir(context)
        if (!libDir.exists()) libDir.mkdirs()

        val client = OkHttpClient()
        val tag = getReleaseTag(context)
        var completed = 0

        for (libName in ESSENTIAL_LIBS) {
            val targetFile = File(libDir, libName)
            val tempFile = File(libDir, "$libName.tmp")

            if (targetFile.exists() && targetFile.length() > 0) {
                completed++
                _downloadProgress.value = completed.toFloat() / ESSENTIAL_LIBS.size
                continue
            }

            val url = "$RELEASE_BASE$tag/$libName"
            Logger.d(TAG, "Downloading $libName from $url")

            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    response.body?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                
                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(targetFile)
                    completed++
                    _downloadProgress.value = completed.toFloat() / ESSENTIAL_LIBS.size
                } else {
                    return@withContext false
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to download $libName: ${e.message}")
                tempFile.delete()
                return@withContext false
            }
        }
        true
    }

    fun loadAll(context: Context) {
        val systemDir = File(context.applicationInfo.nativeLibraryDir)
        val libDir = getLibDir(context)
        
        for (libName in ESSENTIAL_LIBS) {
            val systemFile = File(systemDir, libName)
            if (systemFile.exists()) {
                System.loadLibrary(libName.removePrefix("lib").removeSuffix(".so"))
            } else {
                val dlcFile = File(libDir, libName)
                if (dlcFile.exists() && dlcFile.length() > 0) {
                    System.load(dlcFile.absolutePath)
                } else {
                    throw IllegalStateException("Missing native library: $libName")
                }
            }
        }
    }
}
