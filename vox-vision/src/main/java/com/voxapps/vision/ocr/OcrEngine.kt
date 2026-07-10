package com.voxapps.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils

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
        private var openCvInitialized = false

        suspend fun create(context: Context, downloader: VisionModelDownloader): OcrEngine {
            if (!openCvInitialized) {
                if (!OpenCVUtils.init(context)) error("Failed to initialize OpenCV native library")
                openCvInitialized = true
            }
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
