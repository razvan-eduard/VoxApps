package com.voxapps.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.voxapps.logging.Logger

private const val TAG = "OcrEngine"

/**
 * Thin wrapper around the vendored `com.paddle.ocr.PaddleOCR`, pointed at the runtime-downloaded
 * model files (see [VisionModelDownloader]) rather than APK assets. Re-created whenever the active
 * zone switches — call [release] on the old instance first.
 */
class OcrEngine private constructor(private val paddleOcr: PaddleOCR) {

    suspend fun recognize(bitmap: Bitmap, tableMode: Boolean = false): String {
        val result = paddleOcr.recognize(bitmap)
        val cells = RowClusterer.cellsOf(result.results)
        // The boxes, once, in a form that can be replayed off-device. Every reconstruction decision
        // is geometry, so a text-only capture cannot reproduce — let alone test — a misread table;
        // this is the input those tests need. Behind the app-level Logger switch like everything
        // else, and one line per cell so a capture stays greppable.
        if (tableMode) {
            Logger.d(TAG, "OCR cells: ${cells.size}")
            cells.forEach { c ->
                Logger.d(TAG, "CELL ${c.xLeft},${c.yTop},${c.xRight},${c.yBottom} ${c.text}")
            }
        }
        // Row order, not detector order: the raw result list follows detection, which scatters a
        // printed row into fragments the moment a photo is skewed or the document is tabular. In
        // table mode the line-breaking additionally uses the reconstruction's non-expanding row
        // anchors (still pure geometry, no column interpretation): the clusterer's expanding rows
        // chain-merge a dense table's short rows into one line, hiding the row boundaries from
        // every downstream reader.
        val plain = (if (tableMode) TableReconstructor.plainRowsText(cells) else null)
            ?: RowClusterer.toTextFromCells(cells)
        // Table mode is strictly ADDITIVE: the plain reading-order text always ships (so a
        // reconstruction misfire on an unseen format can never degrade what the consumer had
        // before table mode existed), and the column-aware reconstruction rides behind a marker
        // for consumers that can validate it deterministically (see expenses' items sum-gate).
        if (tableMode) {
            TableReconstructor.toText(cells)?.let { table ->
                return plain + "\n" + TABLE_SECTION_MARKER + "\n" + table
            }
        }
        return plain
    }

    /**
     * One printed row, with where it sits: text in reading order, box in the pixel coordinates of
     * the bitmap the read was given. The row, not the detector's box, because the tap target a
     * caller builds from this should cover "Tel: 0722 111 222" as one thing even when detection
     * returned it as two.
     */
    data class OcrLine(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    /**
     * The recognized rows with their geometry — the same reading [recognize] flattens to a string,
     * kept in the shape an overlay needs. Same engine, same models, same cost; only what survives
     * the call differs.
     */
    suspend fun read(bitmap: Bitmap): List<OcrLine> {
        val cells = RowClusterer.cellsOf(paddleOcr.recognize(bitmap).results)
        return RowClusterer.rowsOfCells(cells).map { row ->
            OcrLine(
                text = row.sortedBy { it.xLeft }.joinToString(" ") { it.text },
                left = row.minOf { it.xLeft },
                top = row.minOf { it.yTop },
                right = row.maxOf { it.xRight },
                bottom = row.maxOf { it.yBottom }
            )
        }
    }

    suspend fun release() = paddleOcr.release()

    companion object {
        /** Separates the always-present plain text from the appended table reconstruction —
         *  mirrored literally by consumers (see expenses' TableItemsPreParse) without a shared
         *  module, same convention as the stitch-seam marker. */
        const val TABLE_SECTION_MARKER = "--- [table reconstruction] ---"

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
