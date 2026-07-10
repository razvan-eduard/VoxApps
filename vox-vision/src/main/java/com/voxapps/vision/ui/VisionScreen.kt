package com.voxapps.vision.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.voxapps.ipc.VoxOcrResult
import com.voxapps.vision.data.preferences.VisionSettingsRepository
import com.voxapps.vision.di.VisionContainer
import com.voxapps.vision.domain.NoteForwarder
import com.voxapps.vision.domain.OcrResultSender
import com.voxapps.vision.domain.llm.OcrCleanupRequestSender
import com.voxapps.vision.ocr.DocumentCropper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.Locale

/** Consecutive good-framing analysis ticks required before auto-capture fires. */
private const val STABILITY_THRESHOLD = 3

/** Throttle for the live framing analysis — running contour detection on every frame is wasteful. */
private const val ANALYSIS_INTERVAL_MS = 400L

/**
 * Camera capture -> on-device OCR (PaddleOCR via [VisionContainer.ocrEngineForZone]) -> editable
 * text field, standing in front of the same downstream pipeline built earlier: optional LLM cleanup
 * via Commander's generic hook, then either a direct create-note push to Notes (standalone use) or,
 * when [pendingRequest] is set (another satellite asked Vision to scan on its behalf), a reply
 * carrying the raw recognized text.
 *
 * Capture can be triggered manually (the "Scan" button, kept as a fallback for poor lighting or
 * irregular documents) or automatically: a low-frequency [ImageAnalysis] pass reuses
 * [DocumentCropper.hasDocumentQuad] on live preview frames, and once a document-sized quad is found
 * consistently for [STABILITY_THRESHOLD] ticks in a row, capture fires on its own. Auto-capture only
 * refills the recognized-text field, same as a manual tap — sending/saving the note is still a
 * deliberate final action, so a document left in frame after a successful auto-scan can't cause a
 * runaway loop of auto-submits (it can still re-trigger a harmless re-scan; the `armed` latch below
 * requires the frame to first go quad-less before arming the next auto-capture, to avoid burning
 * battery re-scanning the same still document every [STABILITY_THRESHOLD] ticks).
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

    var rawText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var cleanUpWithAi by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(hasCameraPermission()) }
    var isRecognizing by remember { mutableStateOf(false) }
    var liveBounds by remember { mutableStateOf<DocumentCropper.LiveBounds?>(null) }
    var engineReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!cameraGranted) requestCameraPermission { cameraGranted = it }
    }

    // Pre-warms the OCR engine (and, as a side effect, the OpenCV native lib) as soon as the camera
    // is available, so the auto-capture analyzer below isn't racing an uninitialized native lib on
    // its very first frames — the same ordering bug hit earlier with DocumentCropper.
    LaunchedEffect(cameraGranted) {
        if (cameraGranted) {
            try {
                container.ocrEngineForZone(currentZoneOrDefault(container))
                engineReady = true
                android.util.Log.d("VisionScreen", "Engine pre-warm succeeded")
            } catch (t: Throwable) {
                android.util.Log.e("VisionScreen", "Engine pre-warm failed", t)
            }
        }
    }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
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

    // Shared by the manual final button and auto-capture's hands-free completion below.
    val submit: (String) -> Unit = { text ->
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            if (pendingRequest != null) {
                OcrResultSender.send(
                    context,
                    pendingRequest.sourcePackage,
                    VoxOcrResult(task = pendingRequest.task, status = VoxOcrResult.STATUS_SUCCESS, rawText = trimmed)
                )
                finishActivity()
            } else if (cleanUpWithAi) {
                OcrCleanupRequestSender.send(context, trimmed, Locale.getDefault().language)
            } else {
                NoteForwarder.send(context, trimmed, title.ifBlank { null }, category.ifBlank { null })
            }
        }
    }
    val submitState = rememberUpdatedState(submit)

    val isRecognizingState = rememberUpdatedState(isRecognizing)
    val engineReadyState = rememberUpdatedState(engineReady)
    val sensitivitySetting by container.settingsRepository.autoTriggerSensitivityFlow.collectAsState(
        initial = VisionSettingsRepository.DEFAULT_SENSITIVITY
    )
    val sensitivityState = rememberUpdatedState(
        when (sensitivitySetting) {
            "low" -> DocumentCropper.DetectionSensitivity.LOW
            "high" -> DocumentCropper.DetectionSensitivity.HIGH
            else -> DocumentCropper.DetectionSensitivity.MEDIUM
        }
    )

    LaunchedEffect(cameraController) {
        val stability = intArrayOf(0)
        val armed = booleanArrayOf(true)
        val lastAnalysisAt = longArrayOf(0L)
        cameraController.setImageAnalysisAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
            val now = System.currentTimeMillis()
            if (!engineReadyState.value || isRecognizingState.value ||
                now - lastAnalysisAt[0] < ANALYSIS_INTERVAL_MS
            ) {
                image.close()
                return@setImageAnalysisAnalyzer
            }
            lastAnalysisAt[0] = now

            val bounds = try {
                yPlaneToGrayMat(image).let { mat ->
                    try { DocumentCropper.detectLiveBounds(mat, sensitivityState.value) } finally { mat.release() }
                }
            } catch (t: Throwable) {
                android.util.Log.e("VisionScreen", "Framing analysis failed", t)
                null
            } finally {
                image.close()
            }
            liveBounds = bounds

            if (bounds == null) {
                armed[0] = true
                stability[0] = 0
                return@setImageAnalysisAnalyzer
            }
            stability[0]++
            if (armed[0] && stability[0] >= STABILITY_THRESHOLD) {
                armed[0] = false
                stability[0] = 0
                liveBounds = null
                android.util.Log.d("VisionScreen", "Auto-capture triggered (stable framing)")
                captureAndRecognize(
                    context, scope, cameraController, container,
                    onRecognizing = { isRecognizing = it },
                    onResult = { text ->
                        rawText = text
                        // Only the "scan for another satellite" flow has no user-editable fields left
                        // to fill in (Title/Category are hidden when pendingRequest != null) — so an
                        // auto-triggered capture there can go straight through and hand control back to
                        // the caller. Standalone mode still needs a deliberate tap: the user may want to
                        // add a title/category or toggle "clean up with AI" before saving.
                        if (pendingRequest != null) submitState.value(text)
                    }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("app_name")) },
                actions = {
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
                .padding(16.dp),
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
                        .height(320.dp)
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
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(
                                color = Color(0xFF00E676),
                                topLeft = Offset(bounds.left * size.width, bounds.top * size.height),
                                size = Size(
                                    (bounds.right - bounds.left) * size.width,
                                    (bounds.bottom - bounds.top) * size.height
                                ),
                                style = Stroke(width = 4.dp.toPx())
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        captureAndRecognize(
                            context, scope, cameraController, container,
                            onRecognizing = { isRecognizing = it },
                            onResult = { rawText = it }
                        )
                    },
                    enabled = !isRecognizing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRecognizing) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    } else {
                        Text(languageManager.getString("scan_button"))
                    }
                }
            } else {
                Text(languageManager.getString("camera_permission_required"))
            }

            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                label = { Text(languageManager.getString("scan_stub_label")) },
                modifier = Modifier.fillMaxWidth()
            )

            if (pendingRequest == null) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(languageManager.getString("title_optional")) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(languageManager.getString("category_optional")) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row {
                    Checkbox(checked = cleanUpWithAi, onCheckedChange = { cleanUpWithAi = it })
                    Text(
                        languageManager.getString("clean_up_with_ai"),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            Button(
                onClick = { submit(rawText) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(languageManager.getString(if (pendingRequest != null) "send_button" else "save_to_notes"))
            }
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
    onResult: (String) -> Unit
) {
    android.util.Log.d("VisionScreen", "Scan tapped")
    onRecognizing(true)
    cameraController.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                android.util.Log.d("VisionScreen", "Capture succeeded, size=${image.width}x${image.height} format=${image.format} rotation=${image.imageInfo.rotationDegrees}")
                val bitmap = imageProxyToBitmap(image)
                image.close()
                scope.launch {
                    try {
                        val zone = currentZoneOrDefault(container)
                        // ocrEngineForZone() loads the OpenCV native library as a side effect
                        // (OcrEngine.create()) — DocumentCropper needs that done first.
                        val engine = container.ocrEngineForZone(zone)
                        val cropped = withContext(Dispatchers.IO) { DocumentCropper.crop(bitmap) }
                        android.util.Log.d("VisionScreen", "Recognizing with zone=$zone")
                        val text = engine.recognize(cropped)
                        android.util.Log.d("VisionScreen", "Recognized text: $text")
                        onResult(text)
                    } catch (t: Throwable) {
                        android.util.Log.e("VisionScreen", "Recognition failed", t)
                    } finally {
                        onRecognizing(false)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                android.util.Log.e("VisionScreen", "Capture failed", exception)
                onRecognizing(false)
            }
        }
    )
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
