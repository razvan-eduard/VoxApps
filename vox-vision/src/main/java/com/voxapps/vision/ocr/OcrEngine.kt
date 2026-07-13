package com.voxapps.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig

/**
 * Thin wrapper around the vendored `com.paddle.ocr.PaddleOCR`, pointed at the runtime-downloaded
 * model files (see [VisionModelDownloader]) rather than APK assets. Re-created whenever the active
 * zone switches — call [release] on the old instance first.
 */
class OcrEngine private constructor(private val paddleOcr: PaddleOCR) {

    suspend fun recognize(bitmap: Bitmap): String {
        val result = paddleOcr.recognize(bitmap)
        return result.results.joinToString("\n") { it.text }
    }

    suspend fun release() = paddleOcr.release()

    companion object {
        suspend fun create(context: Context, downloader: VisionModelDownloader): OcrEngine {
            // No OpenCVUtils.init()/System.loadLibrary() call here deliberately: vox-vision's release
            // build excludes these libs from the APK (see build.gradle.kts's packaging.jniLibs.excludes)
            // and downloads them as DLC instead — NativeLibManager.loadAll() already System.load()s
            // libopencv_java5.so (and its full dependency chain) by absolute path before the splash
            // screen ever navigates here (gated on NativeLibManager.Status.READY). A second
            // System.loadLibrary("opencv_java5") call would search the APK's own (deliberately empty)
            // native lib dir and always throw UnsatisfiedLinkError, even though the library is already
            // loaded and its JNI bindings already registered.
            val paddleOcr = PaddleOCR.create(
                context = context,
                config = PaddleOCRConfig(recScoreThresh = 0.0f, recBatchSize = 1),
                engineConfig = EngineConfig(),
                detModelFile = downloader.detFile,
                recModelFile = downloader.recFile,
                recConfigFile = downloader.recConfigFile,
            )
            return OcrEngine(paddleOcr)
        }
    }
}
