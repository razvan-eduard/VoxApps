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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.rememberRequirementGate
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.vision.data.preferences.VisionSettingsRepository
import com.voxapps.vision.di.VisionContainer
import com.voxapps.vision.domain.OcrResultSender
import com.voxapps.vision.domain.ScanTargetDiscovery
import com.voxapps.vision.ocr.DocumentCropper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors

/** Floor for the live framing analysis on a background executor (see LaunchedEffect(cameraController)
 *  in [VisionScreen]) — a safety cap on CPU/battery use, not the target rate; actual throughput is
 *  paced by how long each frame's OpenCV processing takes on the device. */
private const val ANALYSIS_INTERVAL_MS = 80L

/**
 * Maps the user's "Capture speed" setting to how many consecutive good-framing analysis ticks are
 * required before auto-capture fires — separate from [VisionSettingsRepository.autoTriggerSensitivityFlow],
 * which controls how easily a SINGLE frame counts as "framed" in the first place. At the original
 * fixed value (3 ticks * 400ms = 1.2s, now "low") capture often fired before the document was
 * well-cropped/focused, feeding OCR a slightly-off frame and producing garbled text (e.g. a legitimate
 * item's name coming out as a two-letter fragment) — "high" (7 ticks, ~2.8s) gives the framing more
 * time to settle first, at the cost of a slower capture.
 */
private fun captureStabilityTicks(setting: String): Int = when (setting) {
    "low" -> 3
    "high" -> 7
    else -> 5 // medium / unknown
}

/**
 * Camera capture -> on-device OCR (PaddleOCR via [VisionContainer.ocrEngineForZone]) -> editable text
 * field, then either a reply to whichever satellite asked Vision to scan on its behalf (when
 * [pendingRequest] is set), or — standalone use — one "send to X" button per installed satellite that
 * can receive a scan (see [ScanTargetDiscovery]), routing through that satellite's own scan-cleanup
 * LLM pipeline exactly like the pendingRequest reply does.
 *
 * Capture can be triggered manually (the "Scan" button, kept as a fallback for poor lighting or
 * irregular documents) or automatically: a low-frequency [ImageAnalysis] pass reuses
 * [DocumentCropper.hasDocumentQuad] on live preview frames, and once a document-sized quad is found
 * consistently for [captureStabilityTicks] ticks in a row, capture fires on its own. Auto-capture only
 * refills the recognized-text field, same as a manual tap — sending/saving the note is still a
 * deliberate final action, so a document left in frame after a successful auto-scan can't cause a
 * runaway loop of auto-submits (it can still re-trigger a harmless re-scan; the `armed` latch below
 * requires the frame to first go quad-less before arming the next auto-capture, to avoid burning
 * battery re-scanning the same still document every [captureStabilityTicks] ticks).
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val languageManager = LocalLanguageManager.current

    // Only the standalone case (no caller waiting) uses the double-press-to-exit pattern — when a
    // satellite launched Vision for a scan, a single back press should return control to it
    // immediately (the default Activity.finish() behavior), not require a second confirming press.
    DoubleBackToExitHandler(
        message = languageManager.getString("press_back_again_to_exit"),
        enabled = pendingRequest == null
    )

    var rawText by remember { mutableStateOf("") }
    var lastScannedUri by remember { mutableStateOf<String?>(null) }
    
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
    var liveBounds by remember { mutableStateOf<DocumentCropper.LiveBounds?>(null) }
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
    LaunchedEffect(pendingRequest) {
        rawText = ""
        lastScannedUri = null
    }

    // Pre-warms the OCR engine (and, as a side effect, the OpenCV native lib) as soon as the camera
    // is available, so the auto-capture analyzer below isn't racing an uninitialized native lib on
    // its very first frames — the same ordering bug hit earlier with DocumentCropper.
    LaunchedEffect(cameraGranted) {
        if (cameraGranted) {
            try {
                container.ocrEngineForZone(currentZoneOrDefault(container))
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

    // Shared by the manual final button (pendingRequest case) and auto-capture's hands-free
    // completion below. Standalone mode has no single "submit" action anymore — the user picks a
    // destination button instead (see the Column of per-target buttons further down).
    val submit: (String, String?, String?) -> Unit = { text, imageUri, aiImageUri ->
        val trimmed = text.trim()
        // showScanSuccess now gates finishActivity() by ~1.2s (see LaunchedEffect below) instead of
        // it happening synchronously right here — without this guard, the still-live auto-capture
        // analyzer can re-arm and fire again during that window, sending a second (or third...)
        // OcrResultSender.send for the same scan.
        if (!showScanSuccess && (trimmed.isNotEmpty() || imageUri != null) && pendingRequest != null) {
            OcrResultSender.send(
                context,
                pendingRequest.sourcePackage,
                VoxOcrResult(
                    task = pendingRequest.task,
                    status = VoxOcrResult.STATUS_SUCCESS,
                    rawText = trimmed.takeIf { it.isNotEmpty() } ?: "Image scan",
                    imageUri = imageUri,
                    aiImageUri = aiImageUri
                )
            )
            showScanSuccess = true
        }
    }
    val submitState = rememberUpdatedState(submit)

    // Holds the confirmation on screen briefly, then (if the caller asked for it) brings that
    // caller's own task back to the front before finishing — see VoxOcrRequest.returnToCallerOnComplete.
    // getLaunchIntentForPackage is package-agnostic on purpose: Vision never needs to know any
    // specific caller's Activity class, keeping the "any first-party satellite" contract intact.
    LaunchedEffect(showScanSuccess) {
        if (!showScanSuccess) return@LaunchedEffect
        delay(1200)
        if (pendingRequest?.returnToCallerOnComplete == true) {
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
    // The analyzer callback below is registered once inside LaunchedEffect(cameraController), which
    // never re-runs (cameraController's identity never changes) — a bare `pendingRequest` reference
    // in that closure would freeze at whatever it was on the *first* composition. If Vision started
    // standalone (null) and a satellite's request arrived later via onNewIntent, or a second request
    // from a different caller arrived while Vision was already open, the stale closure either never
    // auto-submitted at all, or would have submitted back to the wrong caller. rememberUpdatedState
    // keeps this read live.
    val pendingRequestState = rememberUpdatedState(pendingRequest)
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
    val stabilitySetting by container.settingsRepository.autoTriggerStabilityFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_STABILITY
    )
    val stabilityThresholdState = rememberUpdatedState(captureStabilityTicks(stabilitySetting))

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
        val stability = intArrayOf(0)
        val armed = booleanArrayOf(true)
        val lastAnalysisAt = longArrayOf(0L)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        cameraController.setImageAnalysisAnalyzer(analysisExecutor) { image ->
            val now = System.currentTimeMillis()
            if (!engineReadyState.value || isRecognizingState.value ||
                pendingRequestState.value == null || // Don't auto-capture in manual/standalone mode
                showScanSuccessState.value || // Already submitted — stop analyzing during the confirmation delay
                now - lastAnalysisAt[0] < ANALYSIS_INTERVAL_MS // Floor only, not a target rate — see above
            ) {
                image.close()
                if (pendingRequestState.value == null) mainExecutor.execute { liveBounds = null }
                return@setImageAnalysisAnalyzer
            }
            lastAnalysisAt[0] = now

            val bounds = try {
                yPlaneToGrayMat(image).let { mat ->
                    try { DocumentCropper.detectLiveBounds(mat, sensitivityState.value) } finally { mat.release() }
                }
            } catch (t: Throwable) {
                Logger.e("VisionScreen", "Framing analysis failed", t)
                null
            } finally {
                image.close()
            }

            // Compose state writes and captureAndRecognize (which drives the camera + calls back into
            // Compose state) hop back to main — only the OpenCV Mat work above runs off-thread.
            mainExecutor.execute {
                liveBounds = bounds

                if (bounds == null) {
                    armed[0] = true
                    stability[0] = 0
                    return@execute
                }
                stability[0]++
                if (armed[0] && stability[0] >= stabilityThresholdState.value) {
                    armed[0] = false
                    stability[0] = 0
                    liveBounds = null
                    Logger.d("VisionScreen", "Auto-capture triggered (stable framing)")
                    captureAndRecognize(
                        context, scope, cameraController, container,
                        onRecognizing = { isRecognizing = it },
                        onResult = { text, imageUri, aiImageUri ->
                            rawText = text
                            lastScannedUri = imageUri
                            // Only the "scan for another satellite" flow has a single, already-known
                            // destination — so an auto-triggered capture there can go straight through and
                            // hand control back to the caller. Standalone mode always needs a deliberate
                            // tap: there's no way to auto-decide which of N installed targets to send to.
                            // Reads the live pendingRequestState, not the closure-frozen pendingRequest
                            // (see above).
                            if (pendingRequestState.value != null) submitState.value(text, imageUri, aiImageUri)
                        }
                    )
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
            if (pendingRequest != null) {
                Text(
                    pendingRequest.hint
                        ?: String.format(languageManager.getString("scanning_for_caller"), pendingRequest.sourcePackage),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (cameraGranted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f) // Fills available vertical space
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).apply {
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
                    // Manual bypass for the hands-free auto-capture flow (pendingRequest != null
                    // only — standalone mode already has its own per-target capture buttons below).
                    // Hidden once showScanSuccess is up so it can't queue a second capture underneath
                    // the confirmation overlay (a plain Box there doesn't consume touches on its own).
                    if (pendingRequest != null && !showScanSuccess) {
                        FloatingActionButton(
                            onClick = {
                                if (!isRecognizing) {
                                    captureAndRecognize(
                                        context, scope, cameraController, container,
                                        onRecognizing = { isRecognizing = it },
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
                    // covers the FAB above.
                    if (pendingRequest != null && isRecognizing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            } else {
                Text(languageManager.getString("camera_permission_required"))
            }

            // [Manual Mode Only] Action buttons and result text
            if (pendingRequest == null) {
                // Horizontal row for Send buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scanTargets.forEach { target ->
                        val targetGate = rememberRequirementGate(
                            satisfied = commanderInstalled,
                            requiredMessage = languageManager.getString("commander_required_message")
                        ) {
                            captureAndRecognize(
                                context, scope, cameraController, container,
                                onRecognizing = { isRecognizing = it },
                                onResult = { text, imageUri, aiImageUri ->
                                    val trimmed = text.trim()
                                    OcrResultSender.send(
                                        context, target.packageName,
                                        VoxOcrResult(
                                            task = target.task,
                                            status = VoxOcrResult.STATUS_SUCCESS,
                                            rawText = trimmed.takeIf { it.isNotEmpty() } ?: "Image scan",
                                            imageUri = imageUri,
                                            aiImageUri = aiImageUri
                                        )
                                    )
                                    rawText = trimmed
                                    lastScannedUri = imageUri
                                    Toast.makeText(
                                        context,
                                        String.format(languageManager.getString("sent_to_target"), target.label),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                        Button(
                            onClick = targetGate.onClick,
                            enabled = !isRecognizing,
                            modifier = Modifier.weight(1f).alpha(targetGate.alpha) // Equal width for both buttons
                        ) {
                            if (isRecognizing) {
                                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(target.label, maxLines = 1)
                            }
                        }
                    }
                }

                // OCR Text result at the bottom, scrollable area if it gets long
                Box(modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { rawText = it },
                        label = { Text(languageManager.getString("scan_stub_label")) },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = false
                    )
                }
            }
        }
    }
    if (showScanSuccess) {
        ScanSuccessOverlay(text = languageManager.getString("scan_successful"))
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

/** Shared by the manual "Scan" button and the auto-capture analyzer so both go through one path. */
private fun captureAndRecognize(
    context: Context,
    scope: CoroutineScope,
    cameraController: LifecycleCameraController,
    container: VisionContainer,
    onRecognizing: (Boolean) -> Unit,
    onResult: (String, String?, String?) -> Unit
) {
    Logger.d("VisionScreen", "Scan tapped")
    onRecognizing(true)
    cameraController.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                Logger.d("VisionScreen", "Capture succeeded, size=${image.width}x${image.height} format=${image.format} rotation=${image.imageInfo.rotationDegrees}")
                val bitmap = imageProxyToBitmap(image)
                image.close()
                scope.launch {
                    try {
                        val zone = currentZoneOrDefault(container)
                        val engine = container.ocrEngineForZone(zone)
                        val cropped = withContext(Dispatchers.IO) { DocumentCropper.crop(bitmap) }
                        
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
                        val text = engine.recognize(cropped)
                        Logger.d("VisionScreen", "Recognized text: $text")
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

private fun imageProxyToBitmap(image: ImageProxy): android.graphics.Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return decoded
    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
    return android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

/**
 * Builds a grayscale [Mat] directly from an [ImageProxy]'s Y-plane (YUV_420_888's luminance channel
 * is already single-channel grayscale) — used by the live auto-capture framing check, skipping the
 * full YUV->RGB conversion + Bitmap allocation the actual capture path needs, since contour detection
 * only needs luminance. Handles row-stride padding (the Y-plane's stride can exceed the image width).
 *
 * Rotated to match [ImageProxy.getImageInfo]'s `rotationDegrees` — i.e. the same upright orientation
 * the user sees in the preview — so [DocumentCropper.detectLiveBounds]'s returned box can be drawn
 * directly over the preview without the caller needing to redo the rotation math itself.
 */
private fun yPlaneToGrayMat(image: ImageProxy): Mat {
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
    return when (image.imageInfo.rotationDegrees) {
        90 -> { Core.rotate(cropped, rotated, Core.ROTATE_90_CLOCKWISE); cropped.release(); rotated }
        180 -> { Core.rotate(cropped, rotated, Core.ROTATE_180); cropped.release(); rotated }
        270 -> { Core.rotate(cropped, rotated, Core.ROTATE_90_COUNTERCLOCKWISE); cropped.release(); rotated }
        else -> { rotated.release(); cropped }
    }
}
