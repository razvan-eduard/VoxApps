package com.voxapps.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import com.voxapps.logging.Logger
import com.voxapps.vision.ml.docquad.DocQuadDetector
import com.voxapps.vision.ml.docquad.DocQuadOrtRunner
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Standard "document scanner" technique: find the largest four-sided contour in the photo (the
 * paper's edges against the background) and perspective-warp it flat, so OCR runs on just the
 * document instead of the whole scene (background clutter otherwise gets detected as text too — see
 * the Connect Four board-game card mixed into an early test scan). Uses OpenCV's `imgproc` module,
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
     * Fallback threshold for [crop]'s bounding-rect crop, once the stricter quad+perspective-warp
     * path below finds nothing — confirmed on-device, a real photo taken in dim/uneven lighting can
     * fragment the paper's outer edge enough that it never closes into a clean 4-corner contour even
     * though the document is clearly, generously framed (one such shot topped out at ~7.6% by
     * [MIN_AREA_FRACTION]'s stricter accounting), which previously meant [crop] silently returned the
     * *entire, uncropped* photo instead of any crop at all. Lower than [MIN_AREA_FRACTION] since a
     * plain bounding-rect crop only trims background, it doesn't correct perspective/skew, so being
     * looser here is lower-risk than accepting a loose quad for the perspective warp would be.
     */
    private const val FALLBACK_MIN_AREA_FRACTION = 0.05

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

    /**
     * How much of the document's own size to keep beyond each detected edge, in [warp].
     *
     * Corner detection is accurate but not exact, and its errors are not symmetric in consequence:
     * a crop a little too wide costs a band of background that recognises as nothing, while a crop a
     * little too tight costs whatever was printed nearest the edge — and on an invoice that is the
     * totals block and the outermost column. Small enough that the extra band stays background on a
     * normally framed photograph.
     */
    private const val EDGE_MARGIN_FRACTION = 0.025

    private const val DOCQUAD_MODEL_ASSET_PATH = "docquad/docquadnet256_trained_opset17.ort"

    @Volatile
    private var docQuadRunner: DocQuadOrtRunner? = null

    /** Loads the ML corner-detection model (see [DocQuadDetector]) — best-effort, called once
     *  during Vision's engine pre-warm alongside the OCR engine itself. [crop] falls back to the
     *  classical quad/blob detectors below unconditionally if this hasn't been called, or failed. */
    /** Whether the ML corner detector finished loading — lets callers (the live analyzer) decide
     *  whether it's worth paying for a color bitmap conversion on this tick at all. */
    fun isMlDetectorLoaded(): Boolean = docQuadRunner != null

    fun init(context: Context) {
        if (docQuadRunner != null) return
        try {
            docQuadRunner = DocQuadOrtRunner.create(context, DOCQUAD_MODEL_ASSET_PATH)
            Logger.d("DocumentCropper", "DocQuad ML model loaded")
        } catch (t: Throwable) {
            Logger.e("DocumentCropper", "DocQuad ML model failed to load, using classical detection only", t)
        }
    }

    fun crop(bitmap: Bitmap): Bitmap {
        val rgba = Mat()
        val gray = Mat()
        try {
            docQuadRunner?.let { runner ->
                DocQuadDetector.detect(runner, bitmap)?.let { quad ->
                    Utils.bitmapToMat(bitmap, rgba)
                    val result = warp(rgba, quad.toOpenCvPoints())
                    Logger.d("DocumentCropper", "crop: ML quad warp -> ${result.width}x${result.height}")
                    return result
                }
            }
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            findDocumentQuad(gray, MIN_AREA_FRACTION)?.let {
                val result = warp(rgba, it)
                Logger.d("DocumentCropper", "crop: quad warp -> ${result.width}x${result.height}")
                return result
            }
            // Quad detection needs the full boundary to close into 4 clean corners — dim/uneven
            // lighting can fragment it even when the document is clearly, generously framed (see
            // FALLBACK_MIN_AREA_FRACTION's doc comment). A plain axis-aligned crop to the largest
            // blob's bounding rect is still strictly better than keeping the whole background.
            findLargestBlobRect(gray, FALLBACK_MIN_AREA_FRACTION)?.let { rect ->
                // The same margin the warp keeps, here limited by the photograph's own edges since
                // an axis-aligned crop can only take pixels that exist.
                val padX = (rect.width * EDGE_MARGIN_FRACTION).toInt()
                val padY = (rect.height * EDGE_MARGIN_FRACTION).toInt()
                val left = (rect.x - padX).coerceAtLeast(0)
                val top = (rect.y - padY).coerceAtLeast(0)
                val right = (rect.x + rect.width + padX).coerceAtMost(bitmap.width)
                val bottom = (rect.y + rect.height + padY).coerceAtMost(bitmap.height)
                Logger.d("DocumentCropper", "crop: bounding-rect fallback -> ${right - left}x${bottom - top}")
                return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            }
            Logger.d("DocumentCropper", "crop: no crop, returning original ${bitmap.width}x${bitmap.height}")
            return bitmap
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
     * Finds the best-matching document box for the live "found it" overlay rectangle and
     * auto-capture-by-framing, in priority order: (1) the ML corner detector on [colorBitmapForMl]
     * if the caller supplied one and the model is loaded — by far the most reliable, since it isn't
     * fooled by cluttered/low-contrast backgrounds the way classical edge detection is (confirmed
     * on-device: classical detection matched the *entire frame* as "the document" against a busy rug
     * background); (2) the same corner-angle-scored classical quad detection [crop] uses on [gray]
     * (an already single-channel, *upright* frame — the caller is expected to have rotated it to
     * match what's on-screen); (3) the looser classical blob heuristic, for a document extending past
     * the frame's edge (a long receipt, common when the camera is held close) that never closes into
     * a 4-cornered contour no matter the detector.
     *
     * [colorBitmapForMl] is intentionally optional and caller-throttled (see VisionScreen's analyzer
     * — ML inference is meaningfully heavier per-frame than the classical checks, so it's only worth
     * building the color bitmap this needs every few ticks, not every single one) — pass `null` on
     * ticks where the caller wants the cheap classical-only path instead.
     */
    fun detectLiveBounds(gray: Mat, sensitivity: DetectionSensitivity, colorBitmapForMl: Bitmap? = null): LiveBounds? {
        val width = gray.cols().toFloat()
        val height = gray.rows().toFloat()

        docQuadRunner?.let { runner ->
            colorBitmapForMl?.let { bitmap ->
                DocQuadDetector.detect(runner, bitmap)?.let { quad ->
                    val quadMat = MatOfPoint(*quad.toOpenCvPoints())
                    val rect = try {
                        Geometry.boundingRect(quadMat)
                    } finally {
                        quadMat.release()
                    }
                    val bw = bitmap.width.toFloat()
                    val bh = bitmap.height.toFloat()
                    return LiveBounds(
                        left = rect.x / bw,
                        top = rect.y / bh,
                        right = (rect.x + rect.width) / bw,
                        bottom = (rect.y + rect.height) / bh
                    )
                }
            }
        }

        val quad = findDocumentQuad(gray, sensitivity.minAreaFraction)
        val rect = if (quad != null) {
            val quadMat = MatOfPoint(*quad)
            try {
                Geometry.boundingRect(quadMat)
            } finally {
                quadMat.release()
            }
        } else {
            findLargestBlobRect(gray, sensitivity.minAreaFraction) ?: return null
        }
        return LiveBounds(
            left = rect.x / width,
            top = rect.y / height,
            right = (rect.x + rect.width) / width,
            bottom = (rect.y + rect.height) / height
        )
    }

    /** How much of its own bounding box a candidate blob's contour area must fill to be trusted as
     *  "probably a rectangular object" (a book/document held flat, photographed close to
     *  perpendicular) rather than an irregular patch of background (rug/floor texture, shadow edges).
     *  1.0 would mean a perfect axis-aligned rectangle; real photos never hit that exactly (slight
     *  tilt, curled corners, motion blur softening edges), so this is deliberately forgiving. */
    private const val MIN_RECTANGULARITY = 0.55

    /** Above this fraction of the frame, a blob is more likely to be a dominant background region
     *  (wall, floor, out-of-focus clutter filling most of the shot) than a document a user is
     *  deliberately framing — confirmed on-device: a photo of assorted cables/furniture with no
     *  document in it produced a single near-full-frame "boxy enough" blob that passed the
     *  rectangularity check, since a big enough contiguous same-brightness region is often roughly
     *  rectangular in outline even when it isn't a document at all. */
    private const val MAX_AREA_FRACTION = 0.85

    /** A book/document photographed close to perpendicular has a bounded width:height ratio — never
     *  this extreme in either direction. Rejects thin slivers (a shadow edge, a reflection streak)
     *  that can still have high area *and* near-perfect rectangularity simply by being long and
     *  narrow, which the area/rectangularity checks alone don't catch. */
    private const val MAX_ASPECT_RATIO = 3.0

    /** Pixel-space bounding rect of the best-matching document-like blob in [gray], or `null` if
     *  nothing qualifies. Shared by [detectLiveBounds] (live preview overlay, normalizes this to 0..1)
     *  and [crop]'s fallback (a plain bounding-rect crop when the stricter quad detection below finds
     *  nothing).
     *
     *  Previously picked the single largest contour by area alone — confirmed on-device (screen
     *  recordings + per-frame analysis of the drawn live-preview box, across two separate test passes)
     *  that in a cluttered scene this regularly won on an irregular patch of background instead of the
     *  actual document, and that adding a rectangularity check alone still wasn't enough (a large
     *  enough contiguous clutter region can still look "boxy"). Candidates now have to clear an area
     *  range (not too small, not suspiciously close to filling the whole frame), an aspect-ratio bound,
     *  and the rectangularity bar — and among survivors, the one closest to frame *center* wins rather
     *  than the largest, since a user actively framing something keeps it roughly centered and raw
     *  size stops being a useful tiebreaker once every survivor already looks plausibly document-shaped. */
    private fun findLargestBlobRect(gray: Mat, minAreaFraction: Double): Rect? {
        val blurred = Mat()
        val thresholded = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(blurred, thresholded, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.findContours(thresholded, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageWidth = gray.cols().toDouble()
            val imageHeight = gray.rows().toDouble()
            val imageArea = imageWidth * imageHeight
            val centerX = imageWidth / 2.0
            val centerY = imageHeight / 2.0

            val candidates = contours.mapNotNull { contour ->
                val area = Geometry.contourArea(contour)
                val areaFraction = area / imageArea
                if (areaFraction < minAreaFraction || areaFraction > MAX_AREA_FRACTION) return@mapNotNull null

                val rect = Geometry.boundingRect(contour)
                val rectArea = rect.width.toDouble() * rect.height.toDouble()
                if (rectArea <= 0) return@mapNotNull null

                val aspectRatio = max(rect.width.toDouble(), rect.height.toDouble()) /
                    min(rect.width.toDouble(), rect.height.toDouble())
                if (aspectRatio > MAX_ASPECT_RATIO) return@mapNotNull null

                val rectangularity = area / rectArea
                if (rectangularity < MIN_RECTANGULARITY) return@mapNotNull null

                val rectCenterX = rect.x + rect.width / 2.0
                val rectCenterY = rect.y + rect.height / 2.0
                val centerDistance = hypot(rectCenterX - centerX, rectCenterY - centerY)
                rect to centerDistance
            }
            val chosen = candidates.minByOrNull { (_, centerDistance) -> centerDistance } ?: return null
            return chosen.first
        } finally {
            blurred.release(); thresholded.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Corner-angle bounds for [rectScore] — a real document photographed close to perpendicular
     *  never has a corner this acute or this obtuse; candidates outside this range are rejected
     *  outright (score -1) rather than merely penalized, since a shape with e.g. a 40° corner isn't
     *  "a slightly imperfect rectangle," it's a different shape entirely (a folded page, a shadow
     *  wedge, an unrelated blob that happened to approximate to 4 points). */
    private const val MIN_RECT_CORNER_ANGLE_DEG = 60.0
    private const val MAX_RECT_CORNER_ANGLE_DEG = 120.0

    /** A document's aspect ratio (long edge / short edge) is bounded for the same reason
     *  [MAX_ASPECT_RATIO] exists for the blob fallback — rules out thin slivers that could otherwise
     *  still pass the corner-angle check (a long, narrow, genuinely 4-cornered sliver is still not a
     *  document). */
    private const val MIN_QUAD_ASPECT_RATIO = 0.5
    private const val MAX_QUAD_ASPECT_RATIO = 2.5

    /**
     * Finds the best-scoring 4-corner document-shaped contour in [gray], or `null` if nothing
     * qualifies. Shared by [crop] (perspective warp needs exact corners) and [detectLiveBounds] (live
     * preview overlay, only needs the bounding rect of the result).
     *
     * Adapted (Apache-2.0, GPLv3-compatible) from the corner-scoring approach in
     * [MakeACopy](https://github.com/egdels/makeacopy)'s `OpenCVUtils.detectDocumentCornersWithOpenCV`.
     * Earlier version here picked the first 4-corner match among the 5 largest raw contours by area
     * alone — confirmed on-device (screen recordings across several test passes) that this let the
     * live rectangle latch onto whatever technically-4-cornered shape happened to be biggest, not
     * necessarily the most document-*shaped* one. This version instead: (1) uses adaptive Canny
     * thresholds derived from the frame's own median brightness rather than fixed constants, so the
     * edge map isn't tuned for one lighting condition; (2) morphologically closes the threshold mask
     * first (kernel size scaled to image size) to bridge small gaps in the paper's outer edge before
     * edge detection, instead of dilating the edge map after; (3) scores every valid 4-corner
     * candidate by [rectScore] (how close each corner actually is to 90°, hard-rejecting anything
     * outside [MIN_RECT_CORNER_ANGLE_DEG]..[MAX_RECT_CORNER_ANGLE_DEG]) combined with normalized area,
     * and picks the single *best*-scoring candidate across all contours rather than the first match
     * among the largest few.
     */
    private fun findDocumentQuad(gray: Mat, minAreaFraction: Double): Array<Point>? {
        val blurred = Mat()
        val thresholded = Mat()
        val morph = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(blurred, thresholded, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

            val shortSide = min(gray.cols(), gray.rows())
            var kernelSize = max(5, shortSide / 50)
            if (kernelSize % 2 == 0) kernelSize++
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelSize.toDouble(), kernelSize.toDouble()))
            Imgproc.morphologyEx(thresholded, morph, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val median = Core.mean(gray).`val`[0]
            val cannyLower = max(0.0, 0.66 * median)
            val cannyUpper = min(255.0, 1.33 * median)
            Imgproc.Canny(morph, edges, cannyLower, cannyUpper)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageArea = gray.rows().toDouble() * gray.cols().toDouble()
            var bestScore = -1.0
            var bestQuad: Array<Point>? = null

            for (contour in contours) {
                val area = Geometry.contourArea(contour)
                if (area < imageArea * minAreaFraction) continue

                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
                    val perimeter = Geometry.arcLength(curve, true)
                    Geometry.approxPolyDP(curve, approx, perimeter * 0.015, true)
                    if (approx.total().toInt() != 4) continue

                    if (!isConvexQuad(approx.toArray())) continue

                    val quad = orderCorners(approx.toArray())
                    val w1 = dist(quad[0], quad[1])
                    val w2 = dist(quad[2], quad[3])
                    val h1 = dist(quad[1], quad[2])
                    val h2 = dist(quad[3], quad[0])
                    val avgWidth = (w1 + w2) / 2.0
                    val avgHeight = (h1 + h2) / 2.0
                    val aspectRatio = avgHeight / (avgWidth + 1e-9)
                    if (aspectRatio < MIN_QUAD_ASPECT_RATIO || aspectRatio > MAX_QUAD_ASPECT_RATIO) continue

                    val rectRaw = rectScore(quad)
                    if (rectRaw < 0.0) continue

                    val areaNorm = area / imageArea
                    val score = 0.6 * areaNorm + 0.4 * (rectRaw / 120.0)
                    if (score > bestScore) {
                        bestScore = score
                        bestQuad = quad
                    }
                } finally {
                    curve.release()
                    approx.release()
                }
            }
            return bestQuad
        } finally {
            blurred.release(); thresholded.release(); morph.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** True if the (not necessarily ordered) 4 points form a convex quadrilateral — the cross-product
     *  z-component of consecutive edge vectors has the same sign all the way around. This vendored
     *  OpenCV build doesn't expose `Imgproc.isContourConvex`, hence the manual check rather than
     *  relying on the library. A non-convex ("dart"-shaped) quad would need a reflex interior angle at
     *  its concave vertex, which [rectScore]'s 60°..120° bound also tends to catch, but checking
     *  convexity directly is the correct, explicit test rather than leaning on that as a side effect. */
    private fun isConvexQuad(points: Array<Point>): Boolean {
        if (points.size != 4) return false
        var sign = 0.0
        for (i in 0 until 4) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0.0) continue // collinear edge, skip rather than fail on it
            if (sign == 0.0) {
                sign = cross
            } else if ((sign > 0) != (cross > 0)) {
                return false
            }
        }
        return true
    }

    /** Converts DocQuadDetector's OpenCV-agnostic (x, y) pairs into this module's [Point] type. */
    private fun Array<DoubleArray>.toOpenCvPoints(): Array<Point> = Array(size) { i -> Point(this[i][0], this[i][1]) }

    /** Angle in degrees at vertex [a], between rays a->[b] and a->[c]. */
    private fun angleDeg(b: Point, a: Point, c: Point): Double {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val acx = c.x - a.x
        val acy = c.y - a.y
        val num = abx * acx + aby * acy
        val den = hypot(abx, aby) * hypot(acx, acy) + 1e-9
        return Math.toDegrees(acos((num / den).coerceIn(-1.0, 1.0)))
    }

    /** Scores an ordered 4-point quad by how close each corner is to a right angle — 30 points max
     *  per corner (perfect 90°), summed across all 4 (max 120.0). Returns -1.0 if any corner falls
     *  outside [MIN_RECT_CORNER_ANGLE_DEG]..[MAX_RECT_CORNER_ANGLE_DEG] — a hard rejection, not a
     *  penalty, since such a corner means this isn't a document-shaped quad at all. */
    private fun rectScore(q: Array<Point>): Double {
        var score = 0.0
        for (i in 0 until 4) {
            val a = q[i]
            val prev = q[(i + 3) % 4]
            val next = q[(i + 1) % 4]
            val ang = angleDeg(prev, a, next)
            if (ang.isNaN() || ang.isInfinite()) return -1.0
            if (ang < MIN_RECT_CORNER_ANGLE_DEG || ang > MAX_RECT_CORNER_ANGLE_DEG) return -1.0
            val dev = abs(ang - 90.0)
            val perCorner = 30.0 - dev
            if (perCorner > 0) score += perCorner
        }
        return score
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

        // The document is placed inside a slightly larger canvas rather than filling it exactly, so
        // the warp keeps a margin of whatever surrounded the detected edge.
        //
        // Mapping the four corners straight onto the output rectangle makes the detector's opinion
        // of where the paper ends the definition of where the image ends, and a corner that lands a
        // few pixels inside the sheet silently takes the outermost line of print with it — the
        // totals block and the last column are what sit closest to an edge. Insetting the
        // destination extends the same homography beyond the quad instead of clipping at it, so the
        // border is real photographed pixels, at the same scale, and only runs to black where the
        // photograph itself ended. Recovering a lost line costs far less than the strip of desk this
        // brings with it, which OCR reads as nothing at all.
        val marginX = width * EDGE_MARGIN_FRACTION
        val marginY = height * EDGE_MARGIN_FRACTION
        val src = MatOfPoint2f(topLeft, topRight, bottomRight, bottomLeft)
        val dst = MatOfPoint2f(
            Point(marginX, marginY),
            Point(marginX + width, marginY),
            Point(marginX + width, marginY + height),
            Point(marginX, marginY + height)
        )
        val transform = Geometry.getPerspectiveTransform(src, dst)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(rgba, warped, transform, Size(width + 2 * marginX, height + 2 * marginY))
            val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, result)
            return result
        } finally {
            src.release(); dst.release(); transform.release(); warped.release()
        }
    }

    private fun dist(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
}
