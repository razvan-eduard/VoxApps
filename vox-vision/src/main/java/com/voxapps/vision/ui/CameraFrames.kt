package com.voxapps.vision.ui

import android.content.Context
import androidx.camera.core.ImageProxy
import androidx.compose.ui.unit.IntSize
import com.voxapps.logging.Logger
import com.voxapps.vision.ocr.DocumentCropper
import kotlinx.coroutines.sync.Mutex
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * The frame plumbing both camera screens stand on.
 *
 * [VisionScreen] and [LiveViewScreen] are deliberately separate screens — one captures for a
 * caller, one reads in place — but underneath they are the same camera: the same analysis stream,
 * the same rotation constant, the same coordinate correction, and above all the same native lock.
 * What lives here is exactly that shared floor, so neither screen reaches into the other and the
 * lock can never accidentally become two locks.
 */

/** Floor for the live framing analysis on a background executor (see LaunchedEffect(cameraController)
 *  in [VisionScreen]) — a safety cap on CPU/battery use, not the target rate; actual throughput is
 *  paced by how long each frame's OpenCV processing takes on the device. */
/**
 * The analysis stream's requested resolution (pre-rotation). Both camera screens ask for the same
 * frame size so geometry tuned on one (contour thresholds, text legibility) holds on the other;
 * the default 640x480 is as much too coarse for text as it is for contours.
 */
internal val ANALYSIS_RESOLUTION = android.util.Size(1280, 960)

internal const val ANALYSIS_INTERVAL_MS = 80L

/** Floor used instead of [ANALYSIS_INTERVAL_MS] once the ML corner detector (see
 *  [com.voxapps.vision.ml.docquad.DocQuadDetector]) is loaded — a color-bitmap conversion plus a
 *  forward pass through DocQuadNet-256 is meaningfully heavier per-tick than the classical
 *  grayscale-threshold check, so this paces it less aggressively. ~5 detections/sec is plenty for a
 *  live "is a document framed" overlay — the box only needs to look fluid, not track at full analysis
 *  framerate. */
internal const val ML_ANALYSIS_INTERVAL_MS = 200L

/** Max per-edge drift (as a 0..1 fraction of frame size) between consecutive ticks' [DocumentCropper.
 *  LiveBounds] for auto-capture's framing countdown to treat them as "the same document held in
 *  place" rather than restarting — see LaunchedEffect(cameraController). Also used by [boundsClose] to
 *  decide whether two consecutive *raw* detections agree closely enough to trust a new drawn position. */
internal const val BOUNDS_STABILITY_TOLERANCE = 0.05f

/** Consecutive no-detection ticks required before clearing the drawn live-preview box — see
 *  LaunchedEffect(cameraController)'s `consecutiveMisses` doc comment. */
internal const val LIVE_BOUNDS_MISS_GRACE = 3

internal fun boundsClose(a: DocumentCropper.LiveBounds, b: DocumentCropper.LiveBounds): Boolean =
    kotlin.math.abs(a.left - b.left) < BOUNDS_STABILITY_TOLERANCE &&
        kotlin.math.abs(a.top - b.top) < BOUNDS_STABILITY_TOLERANCE &&
        kotlin.math.abs(a.right - b.right) < BOUNDS_STABILITY_TOLERANCE &&
        kotlin.math.abs(a.bottom - b.bottom) < BOUNDS_STABILITY_TOLERANCE

/**
 * Corrects [bounds] — normalized 0..1 coordinates of the *full* analysis frame — for PreviewView's
 * FIT_CENTER letterboxing, so the drawn box (which shares the same full-size Canvas as the preview
 * box) lines up with where the video content actually sits within that box instead of assuming the
 * video fills it edge to edge. Confirmed on-device via logging both sides: the analysis frame's
 * rotated aspect ratio (e.g. 960x1280 = 0.75) and the actual measured preview box (e.g. 1184x2022 =
 * 0.586) routinely differ — with FIT_CENTER (see the PreviewView factory's doc comment for why that's
 * used over the default FILL_CENTER), the mismatch shows up as letterbox/pillarbox bars rather than a
 * crop, so every detection stays representable (nothing gets clipped away) — this only needs to
 * offset and rescale into the letterboxed sub-rectangle, never drop a result.
 */
internal fun remapForPreviewCrop(
    bounds: DocumentCropper.LiveBounds,
    analysisAspect: Float,
    previewBoxSize: IntSize
): DocumentCropper.LiveBounds? {
    if (previewBoxSize.width <= 0 || previewBoxSize.height <= 0 || analysisAspect <= 0f) return bounds
    val viewAspect = previewBoxSize.width.toFloat() / previewBoxSize.height.toFloat()
    return when {
        analysisAspect > viewAspect -> {
            // Analysis frame relatively wider than the view — FIT_CENTER letterboxes top/bottom,
            // video content fills the box's full width.
            val visibleFraction = viewAspect / analysisAspect
            val padTopBottom = (1f - visibleFraction) / 2f
            bounds.copy(
                top = (padTopBottom + bounds.top * visibleFraction).coerceIn(0f, 1f),
                bottom = (padTopBottom + bounds.bottom * visibleFraction).coerceIn(0f, 1f)
            )
        }
        analysisAspect < viewAspect -> {
            // Analysis frame relatively taller than the view — FIT_CENTER pillarboxes left/right,
            // video content fills the box's full height.
            val visibleFraction = analysisAspect / viewAspect
            val padLeftRight = (1f - visibleFraction) / 2f
            bounds.copy(
                left = (padLeftRight + bounds.left * visibleFraction).coerceIn(0f, 1f),
                right = (padLeftRight + bounds.right * visibleFraction).coerceIn(0f, 1f)
            )
        }
        else -> bounds
    }
}

/**
 * Guards every native OpenCV/OCR entry point the camera screens call into — the live-preview
 * analyzers' [DocumentCropper.detectLiveBounds] (each on its screen's own dedicated
 * single-thread executor) and the capture pipeline's [DocumentCropper.crop]/OCR-engine
 * `recognize()` calls (on `Dispatchers.IO` threads, see `finishRecognition` in [VisionScreen]).
 * The Compose-state gates in each screen (`isRecognizing`/`engineReady`/…) only block *new*
 * analyzer ticks from being scheduled, they don't stop an already-running tick's native calls from
 * overlapping a concurrent capture's native calls, which produced a native SIGSEGV inside
 * libopencv on-device. A coroutine [Mutex] rather than a plain `java.util.concurrent.locks.Lock`
 * is deliberate: the OCR call needs a suspend function (the OCR engine's `recognize()`) to run
 * *inside* the locked section, and the Kotlin compiler flags that as a hard error for a plain
 * `Lock`/`synchronized` ("suspension point is inside a critical section" — a real hazard for
 * blocking locks, since a suspended coroutine might resume on a different thread, risking
 * dispatcher-pool starvation). `Mutex` is coroutine-aware and has no such restriction. The
 * live-preview analyzer callbacks aren't themselves suspend functions, so they acquire the mutex
 * via `runBlocking` — safe there specifically because each analyzer executor is its screen's own
 * dedicated, private single thread, not a shared pool, so blocking it briefly has no starvation
 * risk elsewhere; it only delays the next preview frame, which is already an accepted tradeoff.
 *
 * One lock for the whole process, whichever screen is showing — two locks would be no lock at all.
 */
internal val nativeCvLock = Mutex()

/**
 * The back camera's fixed hardware mounting angle (CameraCharacteristics.SENSOR_ORIENTATION) — a
 * per-device hardware constant, unlike CameraX's own [ImageProxy.getImageInfo]'s rotationDegrees,
 * which LifecycleCameraController drives from a live accelerometer-based OrientationEventListener
 * (see androidx.camera.view.RotationProvider) that can misjudge the phone's tilt shot to shot,
 * especially when photographing something held at an angle rather than a phone held dead level.
 * Confirmed on-device: within one batch session, consecutive captures alternated between
 * rotationDegrees 90 and 0 with the phone held the same way throughout, and the "0" shots came back
 * as raw, unrotated (landscape) sensor frames — CameraX believed the phone was in landscape for
 * those specific shots. Since this app is locked to portrait (screenOrientation="portrait") and
 * always uses the back camera, the correct rotation is always this one fixed value; querying it once
 * from CameraCharacteristics and using it for every capture sidesteps CameraX's live tracking
 * entirely instead of trying to stabilize it. LifecycleCameraController exposes no public API to
 * override or disable that listener, so this is the only fix available short of replacing it with a
 * manually-bound ProcessCameraProvider setup.
 */
internal fun backCameraSensorOrientation(context: Context): Int {
    return try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val backId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        backId
            ?.let { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) }
            ?: 90
    } catch (t: Throwable) {
        Logger.e("CameraFrames", "Failed to query back camera sensor orientation, defaulting to 90", t)
        90
    }
}

/**
 * Converts a YUV_420_888 [ImageProxy] (the live analysis stream's format — unlike capture's single
 * JPEG-compressed plane, this has 3 separate Y/U/V planes with their own row/pixel strides) to an
 * upright RGB [Bitmap], for feeding the live rectangle's ML corner detector (see
 * [DocumentCropper.detectLiveBounds]'s `colorBitmapForMl` parameter), which needs real color/texture
 * information the single-channel grayscale Mat [yPlaneToGrayMat] builds doesn't carry. A direct
 * per-pixel ITU-R BT.601 conversion rather than round-tripping through [android.graphics.YuvImage]'s
 * JPEG compress+decode, which is measurably slower for a per-frame live-preview cost.
 */
internal fun yuvImageProxyToColorBitmap(image: ImageProxy, rotationDegrees: Int): android.graphics.Bitmap {
    val width = image.width
    val height = image.height
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val yRowStride = yPlane.rowStride
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    val argb = IntArray(width * height)
    for (row in 0 until height) {
        val yRowStart = row * yRowStride
        val uvRow = row / 2
        val uRowStart = uvRow * uRowStride
        val vRowStart = uvRow * vRowStride
        for (col in 0 until width) {
            val y = (yBuffer.get(yRowStart + col).toInt() and 0xFF) - 16
            val uvCol = col / 2
            val u = (uBuffer.get(uRowStart + uvCol * uPixelStride).toInt() and 0xFF) - 128
            val v = (vBuffer.get(vRowStart + uvCol * vPixelStride).toInt() and 0xFF) - 128

            val r = (1.164 * y + 1.596 * v).toInt().coerceIn(0, 255)
            val g = (1.164 * y - 0.813 * v - 0.391 * u).toInt().coerceIn(0, 255)
            val b = (1.164 * y + 2.018 * u).toInt().coerceIn(0, 255)
            argb[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    val bitmap = android.graphics.Bitmap.createBitmap(argb, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    if (rotationDegrees == 0) return bitmap
    val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    // The unrotated frame is several MB and dead the moment its rotated copy exists.
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

/**
 * Builds a grayscale [Mat] directly from an [ImageProxy]'s Y-plane (YUV_420_888's luminance channel
 * is already single-channel grayscale) — used by the live auto-capture framing check, skipping the
 * full YUV->RGB conversion + Bitmap allocation the actual capture path needs, since contour detection
 * only needs luminance. Handles row-stride padding (the Y-plane's stride can exceed the image width).
 *
 * Rotated by [rotationDegrees] — the same fixed, hardware [CameraCharacteristics.SENSOR_ORIENTATION]
 * value ([backCameraSensorOrientation]) used for captures, *not* [ImageProxy.getImageInfo]'s own
 * `rotationDegrees` — so [DocumentCropper.detectLiveBounds]'s returned box can be drawn directly over
 * the preview without the caller needing to redo the rotation math itself. Confirmed on-device: using
 * the live per-frame value here reproduced the exact same accelerometer misread already root-caused
 * for capture rotation (see `imageProxyToBitmap`'s doc comment in [VisionScreen]) — an occasional
 * wrongly-rotated analysis frame makes the detected box's coordinates transposed relative to the
 * correctly-oriented preview, rendering as a box that's the wrong shape or partially off-screen even
 * when the document is well-centered.
 */
internal fun yPlaneToGrayMat(image: ImageProxy, rotationDegrees: Int): Mat {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val rowStride = plane.rowStride
    val padded = Mat(image.height, rowStride, CvType.CV_8UC1)
    padded.put(0, 0, bytes)
    val cropped = if (rowStride == image.width) {
        padded
    } else {
        val c = padded.submat(0, image.height, 0, image.width).clone()
        padded.release()
        c
    }
    val rotated = Mat()
    return when (rotationDegrees) {
        90 -> { Core.rotate(cropped, rotated, Core.ROTATE_90_CLOCKWISE); cropped.release(); rotated }
        180 -> { Core.rotate(cropped, rotated, Core.ROTATE_180); cropped.release(); rotated }
        270 -> { Core.rotate(cropped, rotated, Core.ROTATE_90_COUNTERCLOCKWISE); cropped.release(); rotated }
        else -> { rotated.release(); cropped }
    }
}
