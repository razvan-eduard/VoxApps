package com.voxapps.vision.ocr

import android.graphics.Bitmap
import com.voxapps.docread.ScanReading
import com.voxapps.docread.TableItemsPreParse
import com.voxapps.logging.Logger

private const val TAG = "ReadingCascade"

/**
 * Recognise the page again, differently, but only when the first reading did not close.
 *
 * The judge is the arithmetic that already decides whether a scan was read at all: rows that sum to
 * one of the document's own totals to the cent. A pass that closes is kept; a pass that does not is
 * discarded whole. That is what makes trying more renderings safe — a worse rendering cannot leak a
 * figure into the result, because a rendering is accepted or rejected in one piece and never merged
 * with another.
 *
 * Cascade rather than parallel, deliberately. Recognition costs seconds, so a document that already
 * reads pays for exactly one pass; only a page that failed buys a second and a third. And the whole
 * thing is asked only for [com.voxapps.ipc.VoxOcrRequest.tableMode] captures, where there are totals
 * to close against — a note has no arithmetic, so for a note every extra pass would prove nothing.
 *
 * The gate here reads with the built-in patterns only: the per-vendor templates live in the app that
 * asked for the scan, and they are not worth copying across the bus to decide a retry. So this judge
 * is the stricter of the two — it can spend a pass on a page the caller would have read anyway,
 * which costs seconds, and it can hand over a page the caller still cannot read, which is exactly
 * what happens today. Neither can make a reading wrong.
 */
object ReadingCascade {

    /** The order they are tried in: what usually works, then contrast, then tone. */
    private val ORDER = listOf(
        ScanVariants.Variant.NORMAL,
        ScanVariants.Variant.BINARISED,
        ScanVariants.Variant.INVERTED
    )

    /**
     * One rendering's attempt — named, and yielding its text, or null when the rendering itself
     * could not be produced. Kept as a value so the stopping rule can be exercised without a
     * camera, a bitmap or a recogniser.
     */
    internal class Pass(val name: String, val text: suspend () -> String?)

    /**
     * The best text this capture yields. [recognize] is the recogniser call, supplied by the caller
     * so the native lock stays where it is held; this runs inside it.
     */
    suspend fun read(source: Bitmap, recognize: suspend (Bitmap) -> String): String =
        choose(
            ORDER.map { variant ->
                Pass(variant.name) {
                    ScanVariants.render(source, variant)?.let { rendered ->
                        try {
                            recognize(rendered)
                        } finally {
                            // NORMAL hands back the source, which the caller still owns and needs.
                            if (rendered !== source && !rendered.isRecycled) rendered.recycle()
                        }
                    }
                }
            }
        )

    /**
     * The first text that closes, and the first text produced otherwise — never nothing, and never
     * a later pass that failed, so a page that reads no better than before still reads exactly as
     * well as before.
     */
    internal suspend fun choose(passes: List<Pass>): String {
        var first: String? = null
        for (pass in passes) {
            val text = pass.text() ?: continue
            if (first == null) first = text
            if (closes(text)) {
                Logger.d(TAG, "Read closed on the ${pass.name} pass")
                return text
            }
            Logger.d(TAG, "The ${pass.name} pass did not close")
        }
        Logger.d(TAG, "No pass closed — keeping the first reading")
        return first.orEmpty()
    }

    /** Whether this text yields rows that prove themselves against the document's own totals. */
    private fun closes(text: String): Boolean =
        ScanReading.of(text, TableItemsPreParse.plainText(text)).items != null
}
