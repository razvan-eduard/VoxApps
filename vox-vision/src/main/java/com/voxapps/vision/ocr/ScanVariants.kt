package com.voxapps.vision.ocr

import android.graphics.Bitmap
import com.voxapps.logging.Logger
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

private const val TAG = "ScanVariants"

/**
 * The same capture, developed differently.
 *
 * Not three photographs — one, processed three ways, so nothing has to be aligned afterwards and no
 * two readings can disagree about what was on the page. Each rendering is offered to the recogniser
 * whole and judged on its own arithmetic (see [ReadingCascade]); nothing is ever merged, because a
 * merge of two cell sets can produce a figure neither pass actually read.
 *
 * Uses the OpenCV already linked for [DocumentCropper] and the OCR pipeline — no new native
 * dependency, and the same rule applies: this is native work, so it belongs inside the caller's
 * native-CV lock.
 */
object ScanVariants {

    enum class Variant {
        /** The capture as the cropper left it. Always tried first, and usually the only one. */
        NORMAL,

        /**
         * Adaptive threshold to pure black and white. The measured failure on these pages is uneven
         * lighting across a large sheet, which a single global cut-off cannot answer — a threshold
         * computed per neighbourhood can, and it is what separates faint print from grey paper.
         */
        BINARISED,

        /**
         * Tone-reversed. Helps only where the page is light-on-dark — table headings, stamps — and
         * actively hurts ordinary dark-on-light text, which is why it is never applied on its own
         * and comes last, after the renderings that do help everywhere.
         */
        INVERTED
    }

    /**
     * The capture rendered as [variant], or null if the rendering failed — in which case the caller
     * skips that pass rather than failing the scan. [Variant.NORMAL] returns the source itself, so
     * callers must not recycle a returned bitmap that is identical to the one passed in.
     */
    fun render(source: Bitmap, variant: Variant): Bitmap? = when (variant) {
        Variant.NORMAL -> source
        Variant.BINARISED -> transform(source, "binarised") { gray, out ->
            // Gaussian rather than mean, and a block far wider than a glyph: the window has to
            // contain enough paper around a character for "lighter than its surroundings" to mean
            // anything. C is subtracted from the local mean, so a positive value biases towards
            // white and keeps paper grain from developing into speckle the detector reads as text.
            Imgproc.adaptiveThreshold(
                gray, out, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                BLOCK_SIZE, CONSTANT
            )
        }
        Variant.INVERTED -> transform(source, "inverted") { gray, out -> Core.bitwise_not(gray, out) }
    }

    private inline fun transform(source: Bitmap, name: String, apply: (Mat, Mat) -> Unit): Bitmap? {
        val rgba = Mat()
        val gray = Mat()
        val out = Mat()
        return try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            apply(gray, out)
            // Back to colour because the recogniser takes a bitmap, not a single-channel Mat; the
            // three channels carry the same value, so nothing is invented by the widening.
            Imgproc.cvtColor(out, rgba, Imgproc.COLOR_GRAY2RGBA)
            val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, result)
            result
        } catch (e: Exception) {
            // A rendering that cannot be produced costs this pass and nothing else — the reading
            // that already exists still ships.
            Logger.e(TAG, "Could not render the $name variant", e)
            null
        } finally {
            rgba.release()
            gray.release()
            out.release()
        }
    }

    /** Wide enough to hold a character and the paper around it at document capture resolution. */
    private const val BLOCK_SIZE = 31

    /** Bias towards paper, so grain does not develop into speckle. */
    private const val CONSTANT = 15.0
}
