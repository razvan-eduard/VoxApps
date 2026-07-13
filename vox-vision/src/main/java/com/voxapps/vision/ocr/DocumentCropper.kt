package com.voxapps.vision.ocr

import android.graphics.Bitmap
import com.voxapps.logging.Logger
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

/**
 * Standard "document scanner" technique: find the largest four-sided contour in the photo (the
 * paper's edges against the background) and perspective-warp it flat, so OCR runs on just the
 * document instead of the whole scene (background clutter otherwise gets detected as text too — see
 * the "Patru la rând" board-game-card mixed into an early test scan). Uses OpenCV's `imgproc` module,
 * already built from source and linked via `:vendor:ppocr-sdk` for the OCR pipeline itself — no new
 * native dependency.
 *
 * Best-effort: if no clear four-sided document boundary is found (cluttered background, document
 * doesn't fill enough of the frame, curled/torn edges), falls back to the original, uncropped bitmap
 * rather than failing the scan — a wrong crop would be worse than no crop.
 */
object DocumentCropper {

    /** Minimum fraction of the photo's area the detected document must cover to be trusted. */
    private const val MIN_AREA_FRACTION = 0.15

    /**
     * User-adjustable trigger threshold for [hasDocumentQuad]'s live-preview check: analysis frames are
     * lower-resolution and less sharp (no focus lock, unlike a deliberate capture) — confirmed on-device,
     * the same scene that crops cleanly at capture resolution topped out well under 10% of the frame
     * during live analysis, and how well-formed that contour is depends heavily on lighting/background
     * contrast, which varies a lot per user/scene. LOW = fewer false triggers but may miss real documents
     * in poor contrast; HIGH = catches more real documents but is more likely to fire on background
     * clutter. A missed auto-capture just leaves the manual "Scan" button; a false trigger just fires a
     * capture a bit early — neither is catastrophic, so this is a user preference, not a fixed constant.
     */
    enum class DetectionSensitivity(internal val minAreaFraction: Double) {
        LOW(0.15),
        MEDIUM(0.08),
        HIGH(0.03)
    }

    fun crop(bitmap: Bitmap): Bitmap {
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val corners = findDocumentQuad(gray, MIN_AREA_FRACTION) ?: return bitmap
            return warp(rgba, corners)
        } catch (t: Throwable) {
            return bitmap
        } finally {
            rgba.release(); gray.release()
        }
    }

    /**
     * Normalized (0..1) bounding box of the detected document, for drawing a live "found it" overlay
     * rectangle on the camera preview. `null` if nothing large enough was found.
     */
    data class LiveBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Finds a large-enough bright, paper-colored blob in [gray] (an already single-channel, *upright*
     * frame — the caller is expected to have rotated it to match what's on-screen) — a lightweight
     * framing check for live camera preview frames, used to auto-trigger capture once a
     * document looks present (and to draw a bounding rectangle over it) instead of requiring a manual
     * tap every time.
     *
     * Deliberately NOT the same edge/quad-contour detection [crop] uses: confirmed on-device, a
     * document that extends past the frame's edge (a long receipt, common when the camera is held
     * close) never closes into a 4-cornered contour no matter the area threshold — Canny+approxPolyDP
     * needs the full boundary visible. A live framing check only needs a go/no-go signal and a rough
     * box, not exact corners, so brightness thresholding (Otsu) + largest-blob-area is far more
     * tolerant of a partially-framed or low-contrast-edged document than requiring a closed quad. The
     * stricter quad-based [crop] is unchanged — it still needs real corners for the perspective warp.
     */
    fun detectLiveBounds(gray: Mat, sensitivity: DetectionSensitivity): LiveBounds? {
        val blurred = Mat()
        val thresholded = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(blurred, thresholded, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.findContours(thresholded, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageArea = gray.rows().toDouble() * gray.cols().toDouble()
            val largest = contours.maxByOrNull { Geometry.contourArea(it) } ?: return null
            if (Geometry.contourArea(largest) / imageArea < sensitivity.minAreaFraction) return null

            val rect = Geometry.boundingRect(largest)
            val width = gray.cols().toFloat()
            val height = gray.rows().toFloat()
            return LiveBounds(
                left = rect.x / width,
                top = rect.y / height,
                right = (rect.x + rect.width) / width,
                bottom = (rect.y + rect.height) / height
            )
        } finally {
            blurred.release(); thresholded.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun findDocumentQuad(gray: Mat, minAreaFraction: Double): Array<Point>? {
        val blurred = Mat()
        val edges = Mat()
        val dilated = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 75.0, 200.0)
            Imgproc.dilate(edges, dilated, Mat.ones(3, 3, CvType.CV_8U))
            Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageArea = gray.rows().toDouble() * gray.cols().toDouble()
            val topCandidates = contours.sortedByDescending { Geometry.contourArea(it) }.take(5)
            Logger.d(
                "DocumentCropper",
                "size=${gray.cols()}x${gray.rows()} contours=${contours.size} " +
                    "topAreaFraction=${topCandidates.firstOrNull()?.let { Geometry.contourArea(it) / imageArea }}"
            )
            return topCandidates.firstNotNullOfOrNull { contour -> quadCorners(contour, imageArea, minAreaFraction) }
        } finally {
            blurred.release(); edges.release(); dilated.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Returns the 4 ordered corners of [contour] if it approximates a large-enough quadrilateral. */
    private fun quadCorners(contour: MatOfPoint, imageArea: Double, minAreaFraction: Double): Array<Point>? {
        if (Geometry.contourArea(contour) < imageArea * minAreaFraction) return null

        val contour2f = MatOfPoint2f(*contour.toArray())
        val approx2f = MatOfPoint2f()
        try {
            val perimeter = Geometry.arcLength(contour2f, true)
            Geometry.approxPolyDP(contour2f, approx2f, 0.02 * perimeter, true)
            val points = approx2f.toArray()
            if (points.size != 4) return null
            return orderCorners(points)
        } finally {
            contour2f.release()
            approx2f.release()
        }
    }

    /** Orders 4 unordered corner points as [topLeft, topRight, bottomRight, bottomLeft]. */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        val bySum = points.sortedBy { it.x + it.y }
        val topLeft = bySum.first()
        val bottomRight = bySum.last()
        val byDiff = points.sortedBy { it.x - it.y }
        val bottomLeft = byDiff.first()
        val topRight = byDiff.last()
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun warp(rgba: Mat, corners: Array<Point>): Bitmap {
        val (topLeft, topRight, bottomRight, bottomLeft) = corners

        val width = max(dist(bottomRight, bottomLeft), dist(topRight, topLeft))
        val height = max(dist(topRight, bottomRight), dist(topLeft, bottomLeft))

        val src = MatOfPoint2f(topLeft, topRight, bottomRight, bottomLeft)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(width, 0.0), Point(width, height), Point(0.0, height)
        )
        val transform = Geometry.getPerspectiveTransform(src, dst)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(rgba, warped, transform, Size(width, height))
            val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, result)
            return result
        } finally {
            src.release(); dst.release(); transform.release(); warped.release()
        }
    }

    private fun dist(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
}
