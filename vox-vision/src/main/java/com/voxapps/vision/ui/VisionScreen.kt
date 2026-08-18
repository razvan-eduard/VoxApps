package com.voxapps.vision.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.voxapps.logging.Logger
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.SpeedDialAction
import com.voxapps.design.SpeedDialFab
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.vision.data.preferences.VisionSettingsRepository
import com.voxapps.vision.di.VisionContainer
import com.voxapps.vision.domain.OcrResultSender
import com.voxapps.vision.domain.ScanTargetDiscovery
import com.voxapps.vision.ocr.ContinuityMatcher
import com.voxapps.vision.ocr.DocumentCropper
import com.voxapps.vision.ocr.ReadingCascade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors

/** Floor for the live framing analysis on a background executor (see LaunchedEffect(cameraController)
 *  in [VisionScreen]) — a safety cap on CPU/battery use, not the target rate; actual throughput is
 *  paced by how long each frame's OpenCV processing takes on the device. */
private const val ANALYSIS_INTERVAL_MS = 80L

/** Floor used instead of [ANALYSIS_INTERVAL_MS] once the ML corner detector (see
 *  [com.voxapps.vision.ml.docquad.DocQuadDetector]) is loaded — a color-bitmap conversion plus a
 *  forward pass through DocQuadNet-256 is meaningfully heavier per-tick than the classical
 *  grayscale-threshold check, so this paces it less aggressively. ~5 detections/sec is plenty for a
 *  live "is a document framed" overlay — the box only needs to look fluid, not track at full analysis
 *  framerate. */
private const val ML_ANALYSIS_INTERVAL_MS = 200L

/** Max per-edge drift (as a 0..1 fraction of frame size) between consecutive ticks' [DocumentCropper.
 *  LiveBounds] for auto-capture's framing countdown to treat them as "the same document held in
 *  place" rather than restarting — see LaunchedEffect(cameraController). Also used by [boundsClose] to
 *  decide whether two consecutive *raw* detections agree closely enough to trust a new drawn position. */
private const val BOUNDS_STABILITY_TOLERANCE = 0.05f

/** Consecutive no-detection ticks required before clearing the drawn live-preview box — see
 *  LaunchedEffect(cameraController)'s `consecutiveMisses` doc comment. */
private const val LIVE_BOUNDS_MISS_GRACE = 3

private fun boundsClose(a: DocumentCropper.LiveBounds, b: DocumentCropper.LiveBounds): Boolean =
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
private fun remapForPreviewCrop(
    bounds: DocumentCropper.LiveBounds,
    analysisAspect: Float,
    previewBoxSize: androidx.compose.ui.unit.IntSize
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
 * Guards every native OpenCV/OCR entry point this screen calls into — the live-preview analyzer's
 * [DocumentCropper.detectLiveBounds] (on its own dedicated [Executors.newSingleThreadExecutor] thread)
 * and the capture pipeline's [DocumentCropper.crop]/OCR-engine `recognize()` calls (on `Dispatchers.IO`
 * threads, see [finishRecognition]). Before stitch mode existed, attachment captures never ran OCR
 * (always `produceOCR=false`), so the analyzer thread and the capture pipeline never both did heavy
 * native work at the same wall-clock moment. Stitch forces OCR on (see [effectiveProduceOCR]), which
 * opened exactly that window — the Compose-state gates below (`isRecognizing`/`engineReady`/
 * `showScanSuccess`) only block *new* analyzer ticks from being scheduled, they don't stop an
 * already-running tick's native calls from overlapping a concurrent capture's native calls, which
 * produced a native SIGSEGV inside libopencv on-device. A coroutine [Mutex] rather than a plain
 * `java.util.concurrent.locks.Lock` is deliberate: the OCR call ([finishRecognition]) needs a suspend
 * function (the OCR engine's `recognize()`) to run *inside* the locked section, and the Kotlin compiler
 * flags that as a hard error for a plain `Lock`/`synchronized` ("suspension point is inside a critical
 * section" — a real hazard for blocking locks, since a suspended coroutine might resume on a different
 * thread, risking dispatcher-pool starvation). `Mutex` is coroutine-aware and has no such restriction.
 * The live-preview analyzer callback isn't itself a suspend function, so it acquires the mutex via
 * `runBlocking` — safe here specifically because [analysisExecutor] is this screen's own dedicated,
 * private single thread, not a shared pool, so blocking it briefly has no starvation risk elsewhere;
 * it only delays the next preview frame, which is already an accepted tradeoff.
 */
private val nativeCvLock = Mutex()

/** A stitch shot whose text failed the continuity check against the previous accepted shot — held
 *  here while the user decides retake vs. use-anyway (see [VisionScreen]'s `submit`). */
private data class StitchCandidate(val text: String, val imageUri: String, val aiImageUri: String?)

/** BATCH never runs OCR live (a fast tap-tap-tap capture loop — see
 *  [VoxOcrRequest.CAPTURE_MODE_BATCH]'s doc comment); STITCH always does, regardless of the request's
 *  own [VoxOcrRequest.produceOCR], since the continuity check needs text after every shot. SINGLE
 *  keeps [VoxOcrRequest.produceOCR]'s own meaning unchanged. */
private fun effectiveProduceOCR(captureMode: String, requestedProduceOCR: Boolean): Boolean = when (captureMode) {
    VoxOcrRequest.CAPTURE_MODE_BATCH -> false
    VoxOcrRequest.CAPTURE_MODE_STITCH -> true
    else -> requestedProduceOCR
}

/** Joins a stitch session's per-shot texts into the one combined string that goes out as
 *  [VoxOcrResult.rawText] — same `--- Page N ---` separator convention this codebase's other
 *  page-combining call sites use (see e.g. `LineItemsRescanCombiner.combineGroupText` in
 *  vox-expenses), so the LLM on the receiving end sees one clearly-segmented text and produces
 *  exactly one JSON result, never per-shot fragments. */
private fun combineStitchText(texts: List<String>): String =
    texts.joinToString(ContinuityMatcher.STITCH_SEAM_MARKER)

/**
 * Camera capture -> on-device OCR (PaddleOCR via [VisionContainer.ocrEngineForZone]) -> editable text
 * field, then either a reply to whichever satellite asked Vision to scan on its behalf (when
 * [pendingRequest] is set), or — standalone use — one "send to X" button per installed satellite that
 * can receive a scan (see [ScanTargetDiscovery]), routing through that satellite's own scan-cleanup
 * LLM pipeline exactly like the pendingRequest reply does.
 *
 * Capture can be triggered manually (the FAB, always available whenever a target is chosen) or
 * automatically: an [ImageAnalysis] pass reuses [DocumentCropper.detectLiveBounds] on live preview
 * frames, and once a document is framed continuously for the user's configured
 * [VisionSettingsRepository.autoCaptureDelaySecondsFlow] (Manual disables this entirely — only the FAB
 * captures), capture fires on its own. Auto-capture only refills the recognized-text field, same as a
 * manual tap — sending/saving the note is still a deliberate final action, so a document left in frame
 * after a successful auto-scan can't cause a runaway loop of auto-submits (it can still re-trigger a
 * harmless re-scan; the framing timer requires the frame to first go bounds-less, or jump to a
 * different blob, before restarting the countdown). Both the live rectangle and auto-capture are
 * skipped entirely for [VoxOcrRequest.CAPTURE_MODE_STITCH] — each stitch shot is a deliberate
 * close-up of one segment of a larger document, not a whole framed page, so there's no full-page quad
 * to detect; stitch is manual-FAB-only. [DocumentCropper.crop] itself still runs for stitch shots same
 * as any other mode — its quad+perspective-warp path just finds nothing (safely) for a close-up and
 * falls through to its own bounding-rect-around-the-largest-blob fallback.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VisionScreen(
    container: VisionContainer,
    pendingRequest: PendingScanRequest?,
    hasCameraPermission: () -> Boolean,
    requestCameraPermission: ((Boolean) -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    finishActivity: () -> Unit
) {
    // A caller with an already-existing image (see VoxOcrRequest.imageUri) wants OCR text only, no
    // camera UI at all — short-circuits before any of the camera/permission/preview setup below,
    // which this path never needs.
    if (pendingRequest?.imageUri != null) {
        HeadlessOcrScreen(container = container, pendingRequest = pendingRequest, finishActivity = finishActivity)
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val languageManager = LocalLanguageManager.current
    val fixedRotationDegrees = remember { backCameraSensorOrientation(context) }

    // Only the standalone case (no caller waiting) uses the double-press-to-exit pattern — when a
    // satellite launched Vision for a scan, a single back press should return control to it
    // immediately (the default Activity.finish() behavior), not require a second confirming press.
    DoubleBackToExitHandler(
        message = languageManager.getString("press_back_again_to_exit"),
        enabled = pendingRequest == null
    )

    var rawText by remember { mutableStateOf("") }
    var lastScannedUri by remember { mutableStateOf<String?>(null) }
    // BATCH: accumulates cropped photo URIs across the session — see VoxOcrRequest.CAPTURE_MODE_BATCH's
    // doc comment. OCR never runs per shot in this mode, so there's no text to track alongside these;
    // the whole list goes out in one VoxOcrResult when the user taps Done.
    var batchUris by remember { mutableStateOf<List<String>>(emptyList()) }
    // STITCH: accumulates accepted shots' URIs and their already-OCR'd text in parallel (same index
    // meaning in both lists) — see VoxOcrRequest.CAPTURE_MODE_STITCH's doc comment. stitchTexts holds
    // each shot's FULL raw text — needed as-is so the NEXT shot's continuity/overlap check always
    // compares against the true tail of what was actually photographed, not an already-trimmed
    // version. stitchTrimmedTexts holds what actually goes into the final combined result: shot 0's
    // full text, then each later shot with its physically-overlapping lead-in cut (see
    // ContinuityMatcher.trimOverlap) so the rows the user deliberately re-photographed for overlap
    // aren't duplicated in the output.
    var stitchUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var stitchTexts by remember { mutableStateOf<List<String>>(emptyList()) }
    var stitchTrimmedTexts by remember { mutableStateOf<List<String>>(emptyList()) }
    // A just-captured stitch shot whose text failed the continuity check against the previous
    // accepted shot — non-null blocks further shooting until the user picks retake or use-anyway.
    var stitchRetakeCandidate by remember { mutableStateOf<StitchCandidate?>(null) }
    // Standalone mode's per-target speed dial (see the target-picker Row further down) synthesizes a
    // fake PendingScanRequest here once the user picks a target+mode, instead of duplicating the
    // whole capture/batch/stitch state machine above for the "no real caller" case — every existing
    // pendingRequest-driven branch below reads `effectivePendingRequest` instead of the raw parameter,
    // so a standalone target selection gets the exact same single/batch/stitch behavior a real
    // satellite request would. The one place this still needs special-casing is the finish-vs-stay
    // decision in LaunchedEffect(showScanSuccess), which checks the raw `pendingRequest` parameter —
    // there's no real caller to hand control back to for a synthesized session, so it resets back to
    // the target picker instead of finishing the Activity.
    var standaloneTarget by remember { mutableStateOf<PendingScanRequest?>(null) }
    val effectivePendingRequest = pendingRequest ?: standaloneTarget

    val flashSetting by container.settingsRepository.flashModeFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_FLASH
    )
    val flashMode = when (flashSetting) {
        "on" -> ImageCapture.FLASH_MODE_ON
        "off" -> ImageCapture.FLASH_MODE_OFF
        else -> ImageCapture.FLASH_MODE_AUTO
    }

    var cameraGranted by remember { mutableStateOf(hasCameraPermission()) }
    var isRecognizing by remember { mutableStateOf(false) }
    // The just-captured frame, shown frozen (instead of a plain gray/black overlay) behind the
    // processing spinner below — set the instant capture succeeds (see captureAndRecognize's
    // onCaptured), well before crop/OCR finish, and cleared once isRecognizing flips back off.
    var capturedFrameBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var liveBounds by remember { mutableStateOf<DocumentCropper.LiveBounds?>(null) }
    // Pixel size of the camera preview box as actually laid out on screen — needed to correct
    // detectLiveBounds' normalized coordinates (0..1 of the *analysis* frame) for PreviewView's
    // default FILL_CENTER crop. Confirmed on-device (logged both sides): the analysis frame is
    // 1280x960 (rotated 90°, so 960x1280 upright — aspect 0.75), while the actual preview box
    // measured 1184x2022 (aspect 0.586) — a real, sustained mismatch, not a one-off. FILL_CENTER
    // crops ~11% off each side of the wider analysis frame to fill the narrower box, so drawing
    // bounds as raw fractions of the full analysis frame (as if the whole frame were visible) placed
    // the live rectangle wherever the *uncropped* frame's coordinates fell — visibly wrong, and
    // increasingly wrong the further a detection was from center. See remapForPreviewCrop.
    var previewBoxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Whole seconds remaining before auto-capture fires, or null when not counting down (Manual mode,
    // or no document currently framed) — drives the small countdown label near the live rectangle
    // overlay (see the Canvas block below) so a multi-second auto-capture delay doesn't read as Vision
    // silently doing nothing.
    var autoCaptureCountdownSeconds by remember { mutableStateOf<Int?>(null) }
    var engineReady by remember { mutableStateOf(false) }
    // Shown right before finishing for any pendingRequest-driven scan (see `submit` below) — without
    // this, the app just vanishes the instant a scan succeeds, with zero feedback that anything
    // happened, especially jarring when the scan was triggered from a widget or another app.
    var showScanSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!cameraGranted) requestCameraPermission { cameraGranted = it }
    }

    // Clears any leftover recognized text from a previous scan (standalone or a different caller's
    // request) as soon as a new/different pending request comes in via onNewIntent — without this,
    // stale text from before sat in the field looking like it had already scanned something, and
    // the auto-capture analyzer below (a stale-closure bug, fixed alongside this) never re-armed a
    // fresh auto-submit for the new caller either. Runs on first composition too (a no-op, rawText
    // already starts empty).
    LaunchedEffect(effectivePendingRequest) {
        rawText = ""
        lastScannedUri = null
        batchUris = emptyList()
        stitchUris = emptyList()
        stitchTexts = emptyList()
        stitchTrimmedTexts = emptyList()
        stitchRetakeCandidate = null
        liveBounds = null
        autoCaptureCountdownSeconds = null
    }

    // Pre-warms the OCR engine (and, as a side effect, the OpenCV native lib) as soon as the camera
    // is available, so the auto-capture analyzer below isn't racing an uninitialized native lib on
    // its very first frames — the same ordering bug hit earlier with DocumentCropper.
    LaunchedEffect(cameraGranted) {
        if (cameraGranted) {
            try {
                container.ocrEngineForZone(currentZoneOrDefault(container))
                // Best-effort; DocumentCropper.crop() falls back to classical detection on its own
                // if this hasn't finished (or failed) by the time a photo is captured.
                withContext(Dispatchers.IO) { DocumentCropper.init(context) }
                engineReady = true
                Logger.d("VisionScreen", "Engine pre-warm succeeded")
            } catch (t: Throwable) {
                Logger.e("VisionScreen", "Engine pre-warm failed", t)
            }
        }
    }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            imageCaptureFlashMode = flashMode
            // Default analysis resolution (640x480) is too coarse for the document edges to form one
            // continuous contour against a cluttered background — confirmed on-device: the largest
            // contour found topped out around 3-4% of the frame, well under the detection threshold,
            // versus a clean single-quad match at full capture resolution.
            imageAnalysisResolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(1280, 960),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        }
    }

    // Discovered once — installed apps don't change within a single Activity lifetime. Every scan
    // target's OcrResultReceiver unconditionally forwards to Commander's LLM hook for cleanup (no
    // direct-save fallback), so without Commander installed there's nothing a "send to X" tap could
    // actually accomplish — the targets themselves (Notes/Expenses/Calendar) are still discovered
    // and shown, just dimmed with an explanatory toast on tap, rather than force-emptying the whole
    // list with no explanation for why every button vanished.
    val scanTargets = remember { ScanTargetDiscovery.discover(context) }
    val commanderInstalled = remember { VoxAppsDiscovery.isCommanderInstalled(context) }

    val stitchStrictnessSetting by container.settingsRepository.stitchContinuityStrictnessFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_STITCH_STRICTNESS
    )
    // rememberUpdatedState since `submit` may be invoked from the auto-capture analyzer's closure
    // below (registered once, never rebuilt) via `submitState` — see that closure's own doc comment
    // for why a bare capture of a Compose-state-derived value would go stale there.
    val stitchStrictnessState = rememberUpdatedState(ContinuityMatcher.strictnessFromSetting(stitchStrictnessSetting))

    // Shared by the manual final button (pendingRequest case) and auto-capture's hands-free
    // completion below. Standalone mode has no single "submit" action anymore — the user picks a
    // destination button instead (see the Column of per-target buttons further down).
    val submit: (String, String?, String?) -> Unit = { text, imageUri, aiImageUri ->
        when (effectivePendingRequest?.captureMode) {
            VoxOcrRequest.CAPTURE_MODE_BATCH -> {
                // Accumulate silently and keep the camera live for the next shot — no
                // OcrResultSender.send/showScanSuccess/finish here at all. See completeMultiShot
                // below for the single reply that eventually goes out, once the user taps Done.
                if (imageUri != null) batchUris = batchUris + imageUri
            }
            VoxOcrRequest.CAPTURE_MODE_STITCH -> {
                if (imageUri != null) {
                    if (stitchTexts.isEmpty()) {
                        // First shot has nothing to compare against yet — always accepted, and nothing
                        // to trim (it's the anchor every later shot's overlap gets measured against).
                        stitchUris = stitchUris + imageUri
                        stitchTexts = stitchTexts + text
                        stitchTrimmedTexts = stitchTrimmedTexts + text
                    } else {
                        // alignForStitch's non-null-ness IS the continuity decision — see
                        // ContinuityMatcher.findAlignment's doc comment. It also may retroactively
                        // shrink the previous shot's own stored trimmed text (dropping trailing OCR
                        // noise from a bisected row that only became visible once this shot's seam was
                        // found), hence replacing stitchTrimmedTexts' last entry, not just appending.
                        val alignment = ContinuityMatcher.alignForStitch(stitchTrimmedTexts.last(), text, stitchStrictnessState.value)
                        if (alignment != null) {
                            Logger.d(
                                "VisionScreen",
                                "Stitch alignment: previous(before)=\"${stitchTrimmedTexts.last().takeLast(80)}\" " +
                                    "-> previous(after)=\"${alignment.previousTrimmed.takeLast(80)}\" " +
                                    "| next(before)=\"${text.take(80)}\" -> next(after)=\"${alignment.nextTrimmed.take(80)}\""
                            )
                            stitchUris = stitchUris + imageUri
                            stitchTexts = stitchTexts + text
                            stitchTrimmedTexts = stitchTrimmedTexts.dropLast(1) +
                                alignment.previousTrimmed + alignment.nextTrimmed
                        } else {
                            // Never silently drop a shot — the heuristic is fallible, so the user gets
                            // an explicit retake-or-use-anyway choice instead (see the bar UI below).
                            stitchRetakeCandidate = StitchCandidate(text, imageUri, aiImageUri)
                        }
                    }
                }
            }
            else -> {
                val trimmed = text.trim()
                // showScanSuccess now gates finishActivity() by ~1.2s (see LaunchedEffect below)
                // instead of it happening synchronously right here — without this guard, the
                // still-live auto-capture analyzer can re-arm and fire again during that window,
                // sending a second (or third...) OcrResultSender.send for the same scan.
                if (!showScanSuccess && (trimmed.isNotEmpty() || imageUri != null) && effectivePendingRequest != null) {
                    OcrResultSender.send(
                        context,
                        effectivePendingRequest.sourcePackage,
                        VoxOcrResult(
                            task = effectivePendingRequest.task,
                            status = VoxOcrResult.STATUS_SUCCESS,
                            rawText = trimmed.takeIf { it.isNotEmpty() } ?: "Image scan",
                            imageUris = listOfNotNull(imageUri),
                            aiImageUri = aiImageUri
                        )
                    )
                    showScanSuccess = true
                }
            }
        }
    }
    val submitState = rememberUpdatedState(submit)

    // The batch/stitch session's single reply, fired once the user taps Done — reuses the same
    // showScanSuccess/relaunch/finish sequence below unchanged. Batch never has text (see
    // VoxOcrRequest.CAPTURE_MODE_BATCH); stitch joins every accepted shot's already-verified,
    // already-overlap-trimmed text (stitchTrimmedTexts, not the raw stitchTexts — see
    // ContinuityMatcher.trimOverlap) into one combined rawText (see combineStitchText) — the caller
    // only ever sees one string, same as a plain single-shot reply, regardless of how many shots it
    // took to build it.
    val completeMultiShot: () -> Unit = {
        if (effectivePendingRequest != null && !showScanSuccess) {
            when (effectivePendingRequest.captureMode) {
                // Batch capture itself never runs OCR live (effectiveProduceOCR forces it off, so the
                // capture loop above stays fast) — instead every accepted photo gets OCR'd right here,
                // once, the moment the user taps Done. This still runs inside Vision's own foreground
                // Activity (the tap that triggered it), so it can't hit the background-execution walls
                // a caller-side headless relaunch does (see VoxOcrResult.rawTexts' doc comment) — the
                // old design sent imageUris only and made every caller relaunch Vision once per photo
                // afterward, which is exactly what those walls block.
                VoxOcrRequest.CAPTURE_MODE_BATCH -> if (batchUris.isNotEmpty()) {
                    isRecognizing = true
                    scope.launch {
                        val texts = batchUris.map { uriString ->
                            try {
                                // skipCrop deliberately left false (the default) here, unlike stitch
                                // below. An earlier attempt set this true on the theory that re-cropping
                                // an already-cropped image was triggering a native OpenCV SIGSEGV —
                                // wrong theory: the actual crash (confirmed via on-device tombstones) is
                                // inside PaddleOCR's own preprocessing resize step, which runs either
                                // way, and skipCrop=true made it *more* likely to hit by feeding that
                                // resize step the larger near-full-resolution image instead of crop()'s
                                // smaller, normalized output. Batch sessions completed successfully
                                // (notes created) with skipCrop=false before that change; keep this
                                // path as it was confirmed working.
                                val (text, _, _) = recognizeExistingImage(context, container, android.net.Uri.parse(uriString), produceOCR = true)
                                text
                            } catch (t: Throwable) {
                                Logger.e("VisionScreen", "Batch page OCR failed for $uriString", t)
                                ""
                            }
                        }
                        isRecognizing = false
                        OcrResultSender.send(
                            context,
                            effectivePendingRequest.sourcePackage,
                            VoxOcrResult(
                                task = effectivePendingRequest.task,
                                status = VoxOcrResult.STATUS_SUCCESS,
                                imageUris = batchUris,
                                rawTexts = texts
                            )
                        )
                        showScanSuccess = true
                    }
                }
                VoxOcrRequest.CAPTURE_MODE_STITCH -> if (stitchUris.isNotEmpty()) {
                    // Stitch's text is already fully assembled live (combined on every accepted shot),
                    // so sending the reply is near-instant — without a deliberate pause here, the live
                    // camera preview would jump straight to the success overlay with no processing cue
                    // at all, unlike batch (which visibly freezes for as long as its OCR loop takes).
                    // This gives both modes the same "camera froze, something happened" moment.
                    isRecognizing = true
                    scope.launch {
                        delay(400)
                        OcrResultSender.send(
                            context,
                            effectivePendingRequest.sourcePackage,
                            VoxOcrResult(
                                task = effectivePendingRequest.task,
                                status = VoxOcrResult.STATUS_SUCCESS,
                                rawText = combineStitchText(stitchTrimmedTexts),
                                imageUris = stitchUris
                            )
                        )
                        isRecognizing = false
                        showScanSuccess = true
                    }
                }
            }
        }
    }
    // Discards the in-progress session without ever sending a reply — nothing was staged
    // caller-side yet (unlike the old per-shot-round-trip design), so there's nothing for the
    // caller to clean up; the already-cropped cache files are simply left for the OS to reclaim,
    // same acceptable-risk tradeoff already made elsewhere for an abandoned in-flight scan. A real
    // caller's session finishes the Activity same as before; a synthesized standalone target
    // selection just returns to the target picker instead — there's no caller to hand control back
    // to, and the user likely wants to try a different target or mode.
    val cancelMultiShot: () -> Unit = {
        batchUris = emptyList()
        stitchUris = emptyList()
        stitchTexts = emptyList()
        stitchTrimmedTexts = emptyList()
        stitchRetakeCandidate = null
        if (pendingRequest != null) {
            finishActivity()
        } else {
            standaloneTarget = null
        }
    }

    // Holds the confirmation on screen briefly, then (if the caller asked for it) brings that
    // caller's own task back to the front before finishing — see VoxOcrRequest.returnToCallerOnComplete.
    // getLaunchIntentForPackage is package-agnostic on purpose: Vision never needs to know any
    // specific caller's Activity class, keeping the "any first-party satellite" contract intact.
    // Standalone (real pendingRequest == null — covers both a fully idle standalone screen and a
    // just-completed synthesized standaloneTarget session) reuses the same overlay for the same
    // visual confirmation, but never finishes the Activity — the user stays in Vision, returned to
    // the target picker, ready to scan another document to a different target/mode.
    LaunchedEffect(showScanSuccess) {
        if (!showScanSuccess) return@LaunchedEffect
        delay(1200)
        if (pendingRequest == null) {
            showScanSuccess = false
            standaloneTarget = null
            return@LaunchedEffect
        }
        if (pendingRequest.returnToCallerOnComplete) {
            context.packageManager.getLaunchIntentForPackage(pendingRequest.sourcePackage)?.let { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(launchIntent)
            }
        }
        finishActivity()
    }

    val isRecognizingState = rememberUpdatedState(isRecognizing)
    val engineReadyState = rememberUpdatedState(engineReady)
    val showScanSuccessState = rememberUpdatedState(showScanSuccess)
    // Same stale-closure risk as pendingRequestState below: the auto-capture analyzer closure is set
    // up once and never rebuilt, so a bare `flashMode` reference inside it would freeze at whatever
    // the flash setting was when the screen first opened — changing it afterward (in Settings, or via
    // this screen's own flash toggle) would then never take effect for auto-capture, only for the
    // manual capture button (a plain onClick, rebuilt every recomposition, unaffected by this).
    val flashModeState = rememberUpdatedState(flashMode)
    // The analyzer callback below is registered once inside LaunchedEffect(cameraController), which
    // never re-runs (cameraController's identity never changes) — a bare `pendingRequest` reference
    // in that closure would freeze at whatever it was on the *first* composition. If Vision started
    // standalone (null) and a satellite's request arrived later via onNewIntent, or a second request
    // from a different caller arrived while Vision was already open, the stale closure either never
    // auto-submitted at all, or would have submitted back to the wrong caller. rememberUpdatedState
    // keeps this read live.
    val pendingRequestState = rememberUpdatedState(effectivePendingRequest)
    // Blocks the analyzer from auto-firing another shot while a stitch retake decision is pending —
    // same reasoning as showScanSuccessState below, just for a different "camera should pause" state.
    val stitchRetakeCandidateState = rememberUpdatedState(stitchRetakeCandidate)
    val sensitivitySetting by container.settingsRepository.autoTriggerSensitivityFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_SENSITIVITY
    )
    val sensitivityState = rememberUpdatedState(
        when (sensitivitySetting) {
            "low" -> DocumentCropper.DetectionSensitivity.LOW
            "high" -> DocumentCropper.DetectionSensitivity.HIGH
            else -> DocumentCropper.DetectionSensitivity.MEDIUM
        }
    )
    val autoCaptureDelaySetting by container.settingsRepository.autoCaptureDelaySecondsFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_AUTO_CAPTURE_DELAY
    )
    val autoCaptureDelayState = rememberUpdatedState(autoCaptureDelaySetting)

    LaunchedEffect(flashMode) {
        cameraController.imageCaptureFlashMode = flashMode
    }

    // Runs the analyzer off the main thread — Canny+findContours on a 1280x960 frame is heavy enough
    // that doing it on the main executor (the old setup) caused a periodic hitch every analysis tick,
    // capping how often it was safe to run detection. CameraX's ImageAnalysis defaults to
    // STRATEGY_KEEP_ONLY_LATEST, so a background executor naturally self-paces to whatever this device
    // can actually process — no manual throttle needed to avoid queueing up stale frames.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(analysisExecutor) {
        onDispose { analysisExecutor.shutdown() }
    }

    LaunchedEffect(cameraController) {
        // Wall-clock timestamp (millis) of the moment a document was first detected in the current
        // continuous framing — 0L means "not currently counting down" (no document framed, or it was
        // just reset because the framing jumped to a different blob). Replaces the old tick-counter
        // (`stability[0]`/`armed[0]`) now that the delay is a user-facing number of seconds rather than
        // an implicit analyzer-tick count — see VisionSettingsRepository.autoCaptureDelaySecondsFlow.
        val framedSinceMs = longArrayOf(0L)
        val lastAnalysisAt = longArrayOf(0L)
        var lastBounds: DocumentCropper.LiveBounds? = null
        // Raw-detection-vs-raw-detection agreement gate for what's actually drawn — separate from
        // [lastBounds]'s auto-capture-countdown bookkeeping below. Confirmed on-device (screen
        // recording + per-frame pixel analysis of the drawn box's position): findLargestBlobRect's
        // "biggest bright blob wins" heuristic can pick a *different* candidate blob almost every tick
        // in a cluttered scene (the document one frame, a bright patch of background the next), making
        // the displayed rectangle teleport between unrelated positions rather than tracking one object
        // — e.g. one real recording's box left-edge jumped 1200px -> 1032px -> 56px -> 56px -> 1040px
        // within under 2 seconds. Only accepting a new position once two consecutive raw detections
        // agree filters out exactly that kind of one-tick fluke without needing a better detector.
        var lastRawBounds: DocumentCropper.LiveBounds? = null
        var consecutiveMisses = 0
        val mainExecutor = ContextCompat.getMainExecutor(context)
        cameraController.setImageAnalysisAnalyzer(analysisExecutor) { image ->
            val now = System.currentTimeMillis()
            // ML inference is meaningfully heavier per-frame than the classical checks (color bitmap
            // conversion + a real forward pass through DocQuadNet-256, vs. a cheap grayscale
            // threshold+contour scan) — once it's loaded, this uses a longer floor so the live
            // rectangle stays fluid without hammering the CPU on every single analysis tick. The box
            // itself doesn't need to redraw at the full analysis rate to *look* smooth — see
            // lastRawBounds' two-consecutive-ticks-agree gate below, which already only moves it once
            // a new position is confirmed anyway.
            val effectiveIntervalMs = if (DocumentCropper.isMlDetectorLoaded()) ML_ANALYSIS_INTERVAL_MS else ANALYSIS_INTERVAL_MS
            if (!engineReadyState.value || isRecognizingState.value ||
                showScanSuccessState.value || // Already submitted — stop analyzing during the confirmation delay
                stitchRetakeCandidateState.value != null || // Awaiting a stitch retake decision
                // Stitch shots are deliberately close-up captures of one segment of a larger document,
                // not a whole framed page — there's no document-sized quad to detect, so the live
                // rectangle/auto-capture-by-framing feature is meaningless here (and running it would
                // still burn CPU + take the native lock for no benefit). Stitch is manual-FAB-only.
                pendingRequestState.value?.captureMode == VoxOcrRequest.CAPTURE_MODE_STITCH ||
                now - lastAnalysisAt[0] < effectiveIntervalMs // Floor only, not a target rate — see above
            ) {
                image.close()
                return@setImageAnalysisAnalyzer
            }
            lastAnalysisAt[0] = now

            // Rotated (upright) analysis-frame aspect ratio — needed to correct for PreviewView's
            // FILL_CENTER crop before the detected box is drawn. Captured before image.close() below.
            val analysisAspect = if (fixedRotationDegrees == 90 || fixedRotationDegrees == 270) {
                image.height.toFloat() / image.width.toFloat()
            } else {
                image.width.toFloat() / image.height.toFloat()
            }
            val mlBitmap = if (DocumentCropper.isMlDetectorLoaded()) {
                try {
                    yuvImageProxyToColorBitmap(image, fixedRotationDegrees)
                } catch (t: Throwable) {
                    Logger.e("VisionScreen", "Live color-bitmap conversion for ML detection failed", t)
                    null
                }
            } else {
                null
            }
            val bounds = try {
                // See nativeCvLock's doc comment — serializes this against finishRecognition's own
                // OpenCV/OCR calls so no two threads are ever inside native OpenCV code at once. This
                // callback isn't a suspend function, hence runBlocking (safe here — see the doc comment).
                // ONNX inference itself isn't behind this lock (it's a separate native library from
                // OpenCV, no shared state to race), only the OpenCV Mat work classical detection needs.
                runBlocking {
                    nativeCvLock.withLock {
                        yPlaneToGrayMat(image, fixedRotationDegrees).let { mat ->
                            try {
                                DocumentCropper.detectLiveBounds(mat, sensitivityState.value, mlBitmap)
                            } finally {
                                mat.release()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Logger.e("VisionScreen", "Framing analysis failed", t)
                null
            } finally {
                image.close()
                mlBitmap?.recycle()
            }

            // Compose state writes and captureAndRecognize (which drives the camera + calls back into
            // Compose state) hop back to main — only the OpenCV Mat work above runs off-thread.
            mainExecutor.execute {
                // The live rectangle itself is shown regardless of pendingRequest — standalone users
                // get the same framing feedback — but only the "scan for another satellite" flow has a
                // single, already-known destination to auto-capture *and auto-submit* to. Standalone
                // always needs a deliberate tap on one of the target buttons: there's no way to
                // auto-decide which of N installed targets to send to, and auto-capturing here anyway
                // would just mean the eventual target-button tap re-captures a second photo, discarding
                // this one.
                // Corrected for PreviewView's crop before anything downstream (stability tracking,
                // auto-capture countdown, drawing) touches it — see previewBoxSize's doc comment and
                // remapForPreviewCrop. null here means the raw detection, once cropped to what's
                // actually visible, doesn't survive at all (entirely outside the visible region).
                val correctedBounds = bounds?.let { remapForPreviewCrop(it, analysisAspect, previewBoxSize) }
                if (correctedBounds == null) {
                    // A few consecutive misses (not just one) before clearing the drawn box — a
                    // single dropped frame shouldn't blank out an otherwise-good, held-steady framing.
                    consecutiveMisses++
                    lastRawBounds = null
                    if (consecutiveMisses >= LIVE_BOUNDS_MISS_GRACE) {
                        liveBounds = null
                    }
                    framedSinceMs[0] = 0L
                    lastBounds = null
                    autoCaptureCountdownSeconds = null
                    return@execute
                }
                consecutiveMisses = 0
                val rawAgrees = lastRawBounds?.let { boundsClose(correctedBounds, it) } ?: false
                lastRawBounds = correctedBounds
                // Only move the drawn box once two consecutive raw ticks agree — see lastRawBounds'
                // doc comment. First-ever detection (liveBounds still null) is shown immediately
                // rather than waiting a tick, so framing feedback doesn't feel laggy on first framing.
                if (rawAgrees || liveBounds == null) {
                    liveBounds = correctedBounds
                }

                if (pendingRequestState.value == null) return@execute

                // "Stable" means the same document held roughly in place across consecutive frames,
                // not just "something was found again" — panning across a cluttered scene can keep
                // satisfying the area threshold with a *different* blob each tick, so a fresh framing
                // start (not a cancel — bounds is still non-null) resets the countdown rather than
                // letting an unrelated blob's earlier start time count toward this one.
                val previous = lastBounds
                val heldSteady = previous != null && boundsClose(correctedBounds, previous)
                lastBounds = correctedBounds
                if (framedSinceMs[0] == 0L || !heldSteady) {
                    framedSinceMs[0] = now
                }

                val delaySeconds = autoCaptureDelayState.value
                if (delaySeconds == VisionSettingsRepository.AUTO_CAPTURE_MANUAL ||
                    pendingRequestState.value?.captureMode == VoxOcrRequest.CAPTURE_MODE_BATCH
                ) {
                    // Manual or BATCH: never auto-fires, only the FAB captures — no countdown to show either.
                    autoCaptureCountdownSeconds = null
                    return@execute
                }

                val elapsedMs = now - framedSinceMs[0]
                val delayMs = delaySeconds * 1000L
                if (elapsedMs >= delayMs) {
                    framedSinceMs[0] = 0L
                    lastBounds = null
                    liveBounds = null
                    autoCaptureCountdownSeconds = null
                    Logger.d("VisionScreen", "Auto-capture triggered (${delaySeconds}s delay elapsed)")
                    captureAndRecognize(
                        context, scope, cameraController, container, flashModeState.value, fixedRotationDegrees,
                        produceOCR = pendingRequestState.value?.let {
                            effectiveProduceOCR(it.captureMode, it.produceOCR)
                        } ?: true,
                        tableMode = pendingRequestState.value?.tableMode == true,
                        onRecognizing = { recognizing ->
                            if (recognizing) {
                                isRecognizing = true
                            } else {
                                // Not cleared immediately — confirmed via screen recording that
                                // CameraX's Preview briefly renders an unrotated (raw sensor
                                // orientation) frame right as it resumes after a capture on this
                                // device, regardless of PreviewView's implementation mode. Keeping the
                                // opaque freeze overlay up a little longer than strictly needed hides
                                // that resume glitch instead of exposing it the instant recognition
                                // finishes.
                                scope.launch {
                                    delay(350)
                                    capturedFrameBitmap = null
                                    isRecognizing = false
                                }
                            }
                        },
                        onCaptured = { capturedFrameBitmap = it },
                        onResult = { text, imageUri, aiImageUri ->
                            rawText = text
                            lastScannedUri = imageUri
                            submitState.value(text, imageUri, aiImageUri)
                        }
                    )
                } else {
                    autoCaptureCountdownSeconds = ((delayMs - elapsedMs) / 1000L).toInt() + 1
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("app_name")) },
                actions = {
                    IconButton(onClick = {
                        val next = when (flashSetting) {
                            "auto" -> "on"
                            "on" -> "off"
                            else -> "auto"
                        }
                        scope.launch { container.settingsRepository.setFlashMode(next) }
                    }) {
                        val icon = when (flashSetting) {
                            "on" -> Icons.Filled.FlashOn
                            "off" -> Icons.Filled.FlashOff
                            else -> Icons.Filled.FlashAuto
                        }
                        Icon(icon, contentDescription = "Flash mode")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (effectivePendingRequest != null) {
                Text(
                    effectivePendingRequest.hint
                        ?: String.format(languageManager.getString("scanning_for_caller"), effectivePendingRequest.sourcePackage),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Batch/stitch's whole "keep shooting" loop lives entirely inside this screen — the
            // shutter FAB below doubles as "add another", and this bar is how the session actually
            // ends: Done sends the one accumulated VoxOcrResult and Cancel discards everything with
            // no reply sent. Stitch additionally shows a retake-or-use-anyway prompt in place of this
            // bar whenever a shot just failed the continuity check.
            val retakeCandidate = stitchRetakeCandidate
            if (effectivePendingRequest?.captureMode == VoxOcrRequest.CAPTURE_MODE_STITCH && retakeCandidate != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        languageManager.getString("stitch_discontinuity_warning"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { stitchRetakeCandidate = null },
                            modifier = Modifier.weight(1f)
                        ) { Text(languageManager.getString("stitch_retake")) }
                        Button(
                            onClick = {
                                // The continuity check already failed for this shot, so alignForStitch's
                                // own (identical) search will very likely also find nothing — that's the
                                // correct, safe outcome here: we have no confident overlap boundary, so
                                // keep both texts whole rather than risk cutting off content the check
                                // couldn't actually confirm.
                                val alignment = ContinuityMatcher.alignForStitch(stitchTrimmedTexts.last(), retakeCandidate.text, stitchStrictnessState.value)
                                stitchUris = stitchUris + retakeCandidate.imageUri
                                stitchTexts = stitchTexts + retakeCandidate.text
                                stitchTrimmedTexts = if (alignment != null) {
                                    stitchTrimmedTexts.dropLast(1) + alignment.previousTrimmed + alignment.nextTrimmed
                                } else {
                                    stitchTrimmedTexts + retakeCandidate.text
                                }
                                stitchRetakeCandidate = null
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(languageManager.getString("stitch_use_anyway")) }
                    }
                }
            } else if (effectivePendingRequest?.captureMode == VoxOcrRequest.CAPTURE_MODE_BATCH ||
                effectivePendingRequest?.captureMode == VoxOcrRequest.CAPTURE_MODE_STITCH
            ) {
                val capturedCount = if (effectivePendingRequest.captureMode == VoxOcrRequest.CAPTURE_MODE_BATCH) batchUris.size else stitchUris.size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        String.format(languageManager.getString("multi_shot_captured_count"), capturedCount),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = cancelMultiShot) {
                            Text(languageManager.getString("multi_shot_cancel"))
                        }
                        Button(onClick = completeMultiShot, enabled = capturedCount > 0 && !isRecognizing) {
                            Text(languageManager.getString("multi_shot_done"))
                        }
                    }
                }
            }

            if (cameraGranted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f) // Fills available vertical space
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                        .onSizeChanged { previewBoxSize = it }
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                // PERFORMANCE (the default) renders via TextureView, which on some
                                // devices briefly shows a single unrotated (raw sensor orientation)
                                // frame while the Preview use case rebinds after a capture — visible as
                                // the live feed flashing sideways for an instant. COMPATIBLE renders via
                                // SurfaceView instead, which doesn't hit this — confirmed via a screen
                                // recording showing the sideways flash mid-batch-session, between shots.
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                // Default (FILL_CENTER) crops the preview to fill this box, cropping
                                // off whatever doesn't fit its aspect ratio. Confirmed on-device that
                                // box's measured aspect (e.g. 1184x2022) routinely differs from the
                                // analysis stream's own aspect (e.g. 960x1280 upright) — FILL_CENTER's
                                // crop meant the live detection box (computed against the *full*
                                // analysis frame) didn't line up with what FILL_CENTER actually chose
                                // to show. FIT_CENTER shows the whole frame letterboxed instead of
                                // cropping it, which remapForPreviewCrop's math assumes.
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                controller = cameraController
                                cameraController.bindToLifecycle(lifecycleOwner)
                            }
                        }
                    )
                    val bounds = liveBounds
                    if (bounds != null) {
                        // Now that detection runs on a background executor (see
                        // LaunchedEffect(cameraController) above) it updates several times a second
                        // instead of every 400ms — animating each edge smooths those updates into
                        // continuous motion instead of the rectangle visibly snapping each tick.
                        val animSpec = tween<Float>(ANALYSIS_INTERVAL_MS.toInt())
                        val animatedLeft by animateFloatAsState(bounds.left, animSpec, label = "boundsLeft")
                        val animatedTop by animateFloatAsState(bounds.top, animSpec, label = "boundsTop")
                        val animatedRight by animateFloatAsState(bounds.right, animSpec, label = "boundsRight")
                        val animatedBottom by animateFloatAsState(bounds.bottom, animSpec, label = "boundsBottom")
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(
                                color = Color(0xFF00E676),
                                topLeft = Offset(animatedLeft * size.width, animatedTop * size.height),
                                size = Size(
                                    (animatedRight - animatedLeft) * size.width,
                                    (animatedBottom - animatedTop) * size.height
                                ),
                                style = Stroke(width = 4.dp.toPx())
                            )
                        }
                    }
                    // See VisionSettingsRepository.autoCaptureDelaySecondsFlow — with no cue at all, a
                    // multi-second auto-capture delay would read as Vision silently doing nothing.
                    val countdown = autoCaptureCountdownSeconds
                    if (countdown != null) {
                        Text(
                            String.format(languageManager.getString("auto_capture_countdown"), countdown),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 24.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    // Manual bypass for the hands-free auto-capture flow (effectivePendingRequest !=
                    // null only — standalone mode's target picker is shown below instead when there's
                    // no target+mode chosen yet). Hidden once showScanSuccess is up so it can't queue
                    // a second capture underneath the confirmation overlay (a plain Box there doesn't
                    // consume touches on its own), and hidden while a stitch retake decision is
                    // pending — same reasoning.
                    if (effectivePendingRequest != null && !showScanSuccess && stitchRetakeCandidate == null) {
                        FloatingActionButton(
                            onClick = {
                                // engineReady mirrors the analyzer's own engineReadyState gate (see
                                // LaunchedEffect(cameraController)) — without it, a fast manual tap
                                // right after this screen opens could invoke the OCR engine before
                                // container.ocrEngineForZone(...) has finished its cold-start init.
                                if (!isRecognizing && engineReady) {
                                    captureAndRecognize(
                                        context, scope, cameraController, container, flashMode, fixedRotationDegrees,
                                        produceOCR = effectiveProduceOCR(effectivePendingRequest.captureMode, effectivePendingRequest.produceOCR),
                                        skipCrop = effectivePendingRequest.captureMode == VoxOcrRequest.CAPTURE_MODE_STITCH,
                                        tableMode = effectivePendingRequest.tableMode,
                                        onRecognizing = { recognizing ->
                                            isRecognizing = recognizing
                                            if (!recognizing) capturedFrameBitmap = null
                                        },
                                        onCaptured = { capturedFrameBitmap = it },
                                        onResult = { text, imageUri, aiImageUri ->
                                            rawText = text
                                            lastScannedUri = imageUri
                                            submitState.value(text, imageUri, aiImageUri)
                                        }
                                    )
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 20.dp)
                        ) {
                            if (isRecognizing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Filled.PhotoCamera,
                                    contentDescription = languageManager.getString("manual_capture_now")
                                )
                            }
                        }
                    }
                    // isRecognizing flips true synchronously the instant a capture is triggered (see
                    // captureAndRecognize) — well before OCR itself finishes — so this appears
                    // immediately after the live rectangle vanishes on trigger, instead of leaving a
                    // gap where the preview just looks like it's doing nothing. Drawn last so it also
                    // covers the FAB above. Shown for standalone target-button taps too, not just the
                    // pendingRequest FAB/auto-capture — captureAndRecognize always drives isRecognizing
                    // the same way regardless of caller.
                    if (isRecognizing) {
                        val frozenFrame = capturedFrameBitmap
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (frozenFrame != null) {
                                Image(
                                    bitmap = frozenFrame.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // When frozenFrame is non-null, the opaque Image above it already
                                    // fully hides the live camera feed, so this scrim is purely a dark
                                    // tint under the spinner. When it's null (always true for a
                                    // batch/stitch Done tap — capturedFrameBitmap gets cleared back to
                                    // null right after each shot's own OCR finishes, long before Done is
                                    // ever tapped), this scrim is the ONLY thing standing between the
                                    // viewer and the live preview — a translucent alpha here let the
                                    // still-moving camera feed show right through it, undermining the
                                    // whole point of freezing on Done. Fully opaque in that case instead.
                                    .background(Color.Black.copy(alpha = if (frozenFrame != null) 0.35f else 1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                    }
                }
            } else {
                Text(languageManager.getString("camera_permission_required"))
            }

            // [Target picker — no target+mode chosen yet] One speed dial per installed satellite,
            // offering single/stitch/batch (see VoxOcrRequest.captureMode) — picking any action
            // synthesizes `standaloneTarget`, which everything above (camera UI, capture bar, FAB)
            // then drives exactly like a real caller's pendingRequest.
            if (effectivePendingRequest == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scanTargets.forEach { target ->
                        fun pickMode(mode: String) {
                            if (commanderInstalled) {
                                standaloneTarget = PendingScanRequest(
                                    sourcePackage = target.packageName,
                                    task = target.task,
                                    hint = target.label,
                                    returnToCallerOnComplete = false,
                                    imageUri = null,
                                    produceOCR = true,
                                    captureMode = mode
                                )
                            } else {
                                Toast.makeText(context, languageManager.getString("commander_required_message"), Toast.LENGTH_SHORT).show()
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(target.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            Spacer(modifier = Modifier.height(4.dp))
                            SpeedDialFab(
                                actions = listOf(
                                    SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("capture_mode_single")) {
                                        pickMode(VoxOcrRequest.CAPTURE_MODE_SINGLE)
                                    },
                                    SpeedDialAction(Icons.Filled.Layers, languageManager.getString("capture_mode_stitch")) {
                                        pickMode(VoxOcrRequest.CAPTURE_MODE_STITCH)
                                    },
                                    SpeedDialAction(Icons.Filled.BurstMode, languageManager.getString("capture_mode_batch")) {
                                        pickMode(VoxOcrRequest.CAPTURE_MODE_BATCH)
                                    }
                                ),
                                mainIcon = Icons.Filled.PhotoCamera,
                                mainContentDescription = target.label
                            )
                        }
                    }
                }
            }
        }
    }
    if (showScanSuccess) {
        ScanSuccessOverlay(text = languageManager.getString("scan_successful"))
    }
    }
}

/** The [PendingScanRequest.imageUri] branch of [VisionScreen] — no camera, no permission prompt, just
 *  decode the given image, run it through the same OCR pipeline a live capture would, and reply. Runs
 *  once per distinct [pendingRequest] (keyed on the whole object, so a second request while this one's
 *  still in flight — e.g. a fast onNewIntent — starts its own fresh run rather than being ignored). */
@Composable
private fun HeadlessOcrScreen(
    container: VisionContainer,
    pendingRequest: PendingScanRequest,
    finishActivity: () -> Unit
) {
    val context = LocalContext.current
    val languageManager = LocalLanguageManager.current

    LaunchedEffect(pendingRequest) {
        val (text, imageUri, aiImageUri) = try {
            recognizeExistingImage(context, container, android.net.Uri.parse(pendingRequest.imageUri), pendingRequest.produceOCR, tableMode = pendingRequest.tableMode)
        } catch (t: Throwable) {
            Logger.e("VisionScreen", "Headless OCR failed", t)
            Triple("", null, null)
        }
        val trimmed = text.trim()
        OcrResultSender.send(
            context,
            pendingRequest.sourcePackage,
            VoxOcrResult(
                task = pendingRequest.task,
                status = if (trimmed.isNotEmpty() || imageUri != null) VoxOcrResult.STATUS_SUCCESS else VoxOcrResult.STATUS_ERROR,
                rawText = trimmed.takeIf { it.isNotEmpty() },
                imageUris = listOfNotNull(imageUri),
                aiImageUri = aiImageUri,
                error = if (trimmed.isEmpty() && imageUri == null) "OCR failed" else null
            )
        )
        if (pendingRequest.returnToCallerOnComplete) {
            context.packageManager.getLaunchIntentForPackage(pendingRequest.sourcePackage)?.let { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(launchIntent)
            }
        }
        finishActivity()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = languageManager.getString("reading_photo"),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

/** Universal "it worked" confirmation for every pendingRequest-driven scan — shown for a beat right
 *  before [VisionScreen]'s `submit` finishes the activity, since that path otherwise gives the user
 *  zero feedback before the app disappears (especially jarring when triggered from a widget). */
@Composable
private fun ScanSuccessOverlay(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = null,
                tint = Color(0xFF00E676),
                modifier = Modifier.size(64.dp)
            )
            Text(text, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private suspend fun currentZoneOrDefault(container: VisionContainer): String =
    container.settingsRepository.ocrZoneFlow.first()

/** Crop, stage, and recognize text on [bitmap] — shared by the live camera-capture path
 *  ([captureAndRecognize]) and the headless existing-file path ([recognizeExistingImage]), so both
 *  produce the exact same (text, imageUri, aiImageUri) shape regardless of where the bitmap came
 *  from. Throws on failure — callers already wrap this in their own try/catch.
 *
 *  [skipCrop] bypasses [DocumentCropper.crop] entirely, using [bitmap] as-is — for
 *  [VoxOcrRequest.CAPTURE_MODE_STITCH] specifically. Two things independently point at [crop]'s own
 *  OpenCV pipeline (Canny/GaussianBlur/findContours) as the source of the native SIGSEGV crash
 *  confirmed on-device during stitch testing: (1) both crash tombstones captured so far show the
 *  *identical* 8-frame libopencv_imgproc/libopencv_core backtrace, on two different threads, one of
 *  them captured while the live-preview analyzer — the only other native-OpenCV caller — was
 *  provably disabled for the whole session (stitch mode turns it off entirely, see
 *  [VisionScreen]'s class doc comment), ruling out [nativeCvLock]'s analyzer-vs-capture race as the
 *  cause of *this* crash; (2) a close-up stitch shot is exactly the kind of image (no full document
 *  boundary, often small/oddly-proportioned after any crop) most likely to hit a degenerate case in
 *  that pipeline. Since stitch never needed document-boundary detection in the first place (each shot
 *  is a deliberate close-up of a segment, not a whole page), skipping [crop] entirely for stitch both
 *  sidesteps the crash and matches what stitch actually needs — regardless of the exact faulting
 *  instruction inside OpenCV, which the stripped release .so gives no symbols for. Single/batch still
 *  call [crop] normally; if the same crash ever surfaces there too, the fix is a defensive
 *  dimension/sanity check inside [DocumentCropper.crop] itself before it touches Canny/findContours,
 *  not another mode-specific bypass. */
private suspend fun finishRecognition(
    context: Context,
    container: VisionContainer,
    bitmap: android.graphics.Bitmap,
    produceOCR: Boolean = true,
    skipCrop: Boolean = false,
    tableMode: Boolean = false
): Triple<String, String?, String?> {
    val zone = currentZoneOrDefault(container)
    val engine = container.ocrEngineForZone(zone)
    // See nativeCvLock's doc comment — serializes this against the live-preview analyzer's own
    // OpenCV calls so no two threads are ever inside native OpenCV code at once. Kept even though
    // skipCrop now removes the only known trigger for stitch specifically — batch/single still call
    // crop() and still benefit from this serialization against the analyzer.
    val cropped = if (skipCrop) {
        bitmap
    } else {
        withContext(Dispatchers.IO) { nativeCvLock.withLock { DocumentCropper.crop(bitmap) } }
    }

    // Save image synchronously to internal cache for sharing via FileProvider
    val imageUri = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "receipts").apply { mkdirs() }
            val file = File(cacheDir, "rec_${java.util.UUID.randomUUID()}.jpg")
            java.io.FileOutputStream(file).use { out ->
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            FileProvider.getUriForFile(context, "com.voxapps.vision.fileprovider", file).toString()
        } catch (e: Exception) {
            Logger.e("VisionScreen", "Failed to save image", e)
            null
        }
    }

    // Separate, deliberately smaller copy for LLM attachment — off by default
    // (see VisionSettingsRepository.sendPhotoToAiFlow's doc comment) since it
    // costs real tokens on top of the free text above. Downscaled to the user's
    // configured "photo detail" resolution — that's the only thing that actually
    // reduces LLM image-token cost (JPEG quality/color depth don't factor into
    // OpenAI/Gemini's tile-based image tokenization, only pixel dimensions do).
    val aiImageUri = withContext(Dispatchers.IO) {
        if (!container.settingsRepository.sendPhotoToAiFlow.first()) return@withContext null
        try {
            val detail = container.settingsRepository.photoDetailForAiFlow.first()
            val targetLongEdge = VisionSettingsRepository.targetLongEdgePx(detail)
            val scaled = downscaleToLongEdge(cropped, targetLongEdge)
            val cacheDir = File(context.cacheDir, "receipts").apply { mkdirs() }
            val file = File(cacheDir, "ai_${java.util.UUID.randomUUID()}.jpg")
            java.io.FileOutputStream(file).use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            FileProvider.getUriForFile(context, "com.voxapps.vision.fileprovider", file).toString()
        } catch (e: Exception) {
            Logger.e("VisionScreen", "Failed to prepare AI-attachment image", e)
            null
        }
    }

    Logger.d("VisionScreen", "Recognizing with zone=$zone")
    // Same lock as the crop step above — the OCR engine's own native OpenCV/inference work must not
    // overlap the live-preview analyzer either. Wrapped in our own withContext(Dispatchers.IO) (rather
    // than relying on whatever dispatcher finishRecognition happens to be called from) so lock/unlock
    // are guaranteed to run on the same thread regardless of caller — nested withContext calls on the
    // same dispatcher instance don't redispatch, so engine.recognize()'s own internal
    // withContext(Dispatchers.IO) stays on this exact thread too.
    val text = if (produceOCR) {
        withContext(Dispatchers.IO) {
            nativeCvLock.withLock {
                // The lock covers the whole cascade rather than each pass: the renderings are
                // native CV work too, and a retry only ever happens while the preview is already
                // frozen on the captured frame.
                if (tableMode) {
                    ReadingCascade.read(cropped) { engine.recognize(it, tableMode = true) }
                } else {
                    // No totals to close against, so a second pass could only prove nothing.
                    engine.recognize(cropped, tableMode)
                }
            }
        }
    } else {
        ""
    }
    Logger.d("VisionScreen", "Recognized text: $text")
    return Triple(text, imageUri, aiImageUri)
}

/** Headless counterpart to [captureAndRecognize] — decodes an already-existing image (no camera
 *  frame) and runs it through the same [finishRecognition] pipeline. */
private suspend fun recognizeExistingImage(
    context: Context,
    container: VisionContainer,
    sourceUri: android.net.Uri,
    produceOCR: Boolean = true,
    skipCrop: Boolean = false,
    tableMode: Boolean = false
): Triple<String, String?, String?> {
    val bitmap = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) }
    } ?: throw IllegalStateException("Could not decode image at $sourceUri")
    try {
        return finishRecognition(context, container, bitmap, produceOCR, skipCrop, tableMode)
    } finally {
        // Unlike captureAndRecognize's bitmap (which stays alive on screen as the frozen-frame
        // overlay), nothing outside this function ever holds a reference to this one — safe to
        // recycle immediately rather than waiting on GC. Matters specifically for a batch/stitch
        // Done-tap loop calling this several times in a row: each full-resolution (~4064x3048,
        // ~48MB decoded) bitmap left for the GC to eventually reclaim accumulates real native-heap
        // pressure across iterations. Confirmed on-device: a 4-photo batch's completion loop
        // finished photo 1 cleanly, then the whole process died with a native (Binder-death, no
        // Java stack trace) crash starting photo 2.
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/** Shared by the manual "Scan" button and the auto-capture analyzer so both go through one path.
 *  See [finishRecognition]'s [skipCrop] doc comment. */
private fun captureAndRecognize(
    context: Context,
    scope: CoroutineScope,
    cameraController: LifecycleCameraController,
    container: VisionContainer,
    flashMode: Int,
    fixedRotationDegrees: Int,
    produceOCR: Boolean = true,
    skipCrop: Boolean = false,
    tableMode: Boolean = false,
    onRecognizing: (Boolean) -> Unit,
    onCaptured: (android.graphics.Bitmap) -> Unit = {},
    onResult: (String, String?, String?) -> Unit
) {
    // Reapplied here, not just left to LaunchedEffect(flashMode) — CameraX's LifecycleCameraController
    // can silently reset imageCaptureFlashMode internally when it rebinds use cases (e.g. after a
    // lifecycle/permission change), and that rebind isn't something our flashMode-keyed effect would
    // ever notice, since the VALUE it's keyed on hasn't changed even though the controller's own
    // property has been reset underneath it. Setting it fresh immediately before every capture closes
    // that gap regardless of whether the mismatch already happened.
    cameraController.imageCaptureFlashMode = flashMode
    Logger.d("VisionScreen", "Scan tapped, flashMode=$flashMode (controller now reports ${cameraController.imageCaptureFlashMode})")
    onRecognizing(true)
    cameraController.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // fixedRotationDegrees (not image.imageInfo.rotationDegrees) drives the actual
                // correction — logging CameraX's own live value alongside it purely to keep the
                // mismatch visible if it ever recurs. See backCameraSensorOrientation's doc comment.
                Logger.d("VisionScreen", "Capture succeeded, size=${image.width}x${image.height} format=${image.format} cameraXRotation=${image.imageInfo.rotationDegrees} fixedRotation=$fixedRotationDegrees")
                val bitmap = imageProxyToBitmap(image, fixedRotationDegrees)
                image.close()
                onCaptured(bitmap)
                scope.launch {
                    try {
                        val (text, imageUri, aiImageUri) = finishRecognition(context, container, bitmap, produceOCR, skipCrop, tableMode)
                        onResult(text, imageUri, aiImageUri)
                    } catch (t: Throwable) {
                        Logger.e("VisionScreen", "Recognition failed", t)
                        onResult("", null, null)
                    } finally {
                        onRecognizing(false)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Logger.e("VisionScreen", "Capture failed", exception)
                onRecognizing(false)
                onResult("", null, null)
            }
        }
    )
}

/** Scales [bitmap] down so its longer edge is [targetLongEdge]px, preserving aspect ratio. A no-op
 *  (returns [bitmap] unchanged) if it's already at or below that size — never upscales. */
private fun downscaleToLongEdge(bitmap: android.graphics.Bitmap, targetLongEdge: Int): android.graphics.Bitmap {
    val longEdge = maxOf(bitmap.width, bitmap.height)
    if (longEdge <= targetLongEdge) return bitmap
    val scale = targetLongEdge.toFloat() / longEdge
    val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}

private fun imageProxyToBitmap(image: ImageProxy, rotationDegrees: Int): android.graphics.Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (rotationDegrees == 0) return decoded
    val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

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
/**
 * Converts a YUV_420_888 [ImageProxy] (the live analysis stream's format — unlike capture's single
 * JPEG-compressed plane, this has 3 separate Y/U/V planes with their own row/pixel strides) to an
 * upright RGB [Bitmap], for feeding the live rectangle's ML corner detector (see
 * [DocumentCropper.detectLiveBounds]'s `colorBitmapForMl` parameter), which needs real color/texture
 * information the single-channel grayscale Mat [yPlaneToGrayMat] builds doesn't carry. A direct
 * per-pixel ITU-R BT.601 conversion rather than round-tripping through [android.graphics.YuvImage]'s
 * JPEG compress+decode, which is measurably slower for a per-frame live-preview cost.
 */
private fun yuvImageProxyToColorBitmap(image: ImageProxy, rotationDegrees: Int): android.graphics.Bitmap {
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
    return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun backCameraSensorOrientation(context: Context): Int {
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
        Logger.e("VisionScreen", "Failed to query back camera sensor orientation, defaulting to 90", t)
        90
    }
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
 * for capture rotation (see [imageProxyToBitmap]'s doc comment) — an occasional wrongly-rotated
 * analysis frame makes the detected box's coordinates transposed relative to the correctly-oriented
 * preview, rendering as a box that's the wrong shape or partially off-screen even when the document is
 * well-centered.
 */
private fun yPlaneToGrayMat(image: ImageProxy, rotationDegrees: Int): Mat {
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
