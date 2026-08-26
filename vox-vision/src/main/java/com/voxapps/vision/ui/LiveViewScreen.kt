package com.voxapps.vision.ui

import android.widget.Toast
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.DoubleBackToExitHandler
import com.voxapps.design.EntityActions
import com.voxapps.design.openLocationInMaps
import com.voxapps.logging.Logger
import com.voxapps.textmatch.extract.CountryDialing
import com.voxapps.textmatch.extract.LineEntities
import com.voxapps.vision.data.preferences.VisionSettingsRepository
import com.voxapps.vision.di.VisionContainer
import com.voxapps.vision.domain.liveview.LiveViewCategories
import com.voxapps.vision.ocr.DocumentCropper
import com.voxapps.vision.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

private const val TAG = "LiveViewScreen"

/** How long the framing has to hold still before a read is worth its cost — a moving frame would
 *  produce chips for text that is already somewhere else. */
private const val STABLE_BEFORE_READ_MS = 600L

/** The floor between two reads. Recognition costs on the order of a second; nothing about a scene
 *  held steady changes fast enough to justify paying it more often. */
private const val MIN_READ_GAP_MS = 2000L

/**
 * How readily a finished reading is let go of, chosen in settings. The chips must outlive the
 * detector's bad moments — a missed tick or a stray blob is not the document leaving — so every
 * threshold here counts *sustained* evidence, and the persistent default demands a lot of it:
 * as long as the document stays in frame, the chips stay.
 *
 * [missGraceTicks]: consecutive detection-less ticks before the document counts as out of frame.
 * [foreignTicks]: consecutive detections of a rectangle that is not the tracked one before the
 * document counts as replaced (effectively disabled for persistent — replacement almost always
 * passes through out-of-frame anyway). [appearTicks] and [unanchoredStaleMs] are the close-up
 * reading's only signals: a document rectangle arriving where there was none, and plain age.
 */
private data class RescanEagerness(
    val missGraceTicks: Int,
    val foreignTicks: Int,
    val appearTicks: Int,
    val unanchoredStaleMs: Long
)

private fun eagernessOf(setting: String): RescanEagerness = when (setting) {
    "eager" -> RescanEagerness(missGraceTicks = 4, foreignTicks = 5, appearTicks = 2, unanchoredStaleMs = 15_000L)
    "balanced" -> RescanEagerness(missGraceTicks = 10, foreignTicks = 12, appearTicks = 4, unanchoredStaleMs = 30_000L)
    else -> RescanEagerness(missGraceTicks = 25, foreignTicks = Int.MAX_VALUE, appearTicks = 8, unanchoredStaleMs = 60_000L)
}

/** How far apart two rectangles may sit (per edge, 0..1) and still be the same document a moment
 *  later. Generous by design: it absorbs brisk panning between ticks, while a genuinely different
 *  blob across the frame stays foreign. */
private const val SAME_DOCUMENT_TOLERANCE = 0.2f

/**
 * How eagerly the document rectangle moves, chosen in settings. Reading is not scanning: the scan
 * screen wants the box to snap onto a framing before auto-capture fires, while here it sits under
 * the chips, and every twitch it makes the whole overlay makes with it. Each pace is the same
 * three dials turned together — how often frames are analysed, how many consecutive detections
 * must agree before the drawn box moves, and how long the edges take to glide there.
 */
private data class DetectorPace(val intervalMultiplier: Float, val agreeTicks: Int, val glideMs: Int)

private fun paceOf(setting: String): DetectorPace = when (setting) {
    "fast" -> DetectorPace(intervalMultiplier = 1f, agreeTicks = 1, glideMs = 120)
    "calm" -> DetectorPace(intervalMultiplier = 3f, agreeTicks = 3, glideMs = 550)
    else -> DetectorPace(intervalMultiplier = 1.5f, agreeTicks = 2, glideMs = 300)
}

/** How soon a read that found nothing may try again. Shorter than a fruitful reading's life —
 *  an empty answer is usually the blur of the first steady moment, not a fact about the scene —
 *  but not immediate, or a blank wall held steady would be OCR'd in a loop. */
private const val EMPTY_RETRY_MS = 4000L

/** One read line, classified, in normalized 0..1 coordinates of the upright analysis frame — the
 *  same space [DocumentCropper.LiveBounds] uses, so the one remap both travel through is
 *  [remapForPreviewCrop]. */
private data class LiveChip(
    val entity: LineEntities.Entity,
    val box: DocumentCropper.LiveBounds,
    /** The country prefix this reading added to a national phone number — see the completion pass
     *  in the read loop. Kept apart from the value so the frozen table can show the addition as
     *  the addition it is. */
    val addedPrefix: String? = null
)

/** Everything one read pass produced, kept together so invalidation is atomic: the chips, the
 *  document bounds they were pinned against (null for a close-up that had none), and when. */
private data class LiveReading(
    val chips: List<LiveChip>,
    val anchor: DocumentCropper.LiveBounds?,
    val readAtMs: Long
)

/**
 * Vox LiveView: the camera as a reader rather than a capturer.
 *
 * Entered only through its own launcher alias — never from a satellite's OCR request — and
 * deliberately a separate screen from [VisionScreen]: no capture modes, no send-to buttons, no
 * shutter, no auto-capture. The camera looks at a document, and once the framing holds still the
 * recognized lines come back as tappable chips pinned over the text they were read from — a phone
 * number dials, an email opens a draft, a web address opens, an address searches the map, anything
 * else offers search and copy.
 *
 * The document detector keeps running underneath exactly as in the scan screen, whether or not its
 * rectangle is drawn: it is the stability gate that decides when reading is worth a second of the
 * one native thread, and the motion anchor that lets chips ride small hand movement between reads
 * instead of re-running recognition per frame.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiveViewScreen(
    container: VisionContainer,
    hasCameraPermission: () -> Boolean,
    requestCameraPermission: ((Boolean) -> Unit) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val languageManager = LocalLanguageManager.current
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fixedRotationDegrees = remember { backCameraSensorOrientation(context) }

    DoubleBackToExitHandler(
        message = languageManager.getString("press_back_again_to_exit"),
        enabled = true
    )

    var cameraGranted by remember { mutableStateOf(hasCameraPermission()) }
    var engineReady by remember { mutableStateOf(false) }
    var readInFlight by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<LiveReading?>(null) }
    // Where the reading's anchor rectangle is NOW. Each chip is drawn through the affine map that
    // carries the anchor from where it was at read time to here — so panning translates the chips
    // and moving closer scales them, both for free, from geometry the detector already computes.
    var anchorNow by remember { mutableStateOf<DocumentCropper.LiveBounds?>(null) }
    var liveBounds by remember { mutableStateOf<DocumentCropper.LiveBounds?>(null) }
    var previewBoxSize by remember { mutableStateOf(IntSize.Zero) }
    var analysisAspect by remember { mutableStateOf(0.75f) }
    var torchOn by remember { mutableStateOf(false) }

    // The country whose national phone formats a flat digit run may match — see
    // LineEntities.classify. The device's region first (it says which numbering plan the person
    // lives under better than the UI language does), the language as the fallback.
    val country = remember {
        Locale.getDefault().country.takeIf { it.isNotBlank() } ?: Locale.getDefault().language
    }

    val showFrame by container.settingsRepository.liveViewShowFrameFlow.collectAsStateWithLifecycle(
        initialValue = true
    )
    // null while DataStore answers, so the onboarding neither flashes for somebody who has done it
    // nor gets skipped for somebody who has not.
    val onboarded by container.settingsRepository.liveViewOnboardedFlow.collectAsStateWithLifecycle(
        initialValue = null
    )
    val categoryPrefsJson by container.settingsRepository.liveViewCategoryPrefsFlow.collectAsStateWithLifecycle(
        initialValue = null
    )
    val customJson by container.settingsRepository.liveViewCustomCategoriesFlow.collectAsStateWithLifecycle(
        initialValue = null
    )
    val categoryPrefs = remember(categoryPrefsJson) { LiveViewCategories.prefsFromJson(categoryPrefsJson) }
    val customCategories = remember(customJson) { LiveViewCategories.customFromJson(customJson) }
    // Compiled once per settings change, read per line by the analyzer thread.
    val classifyOptionsState = rememberUpdatedState(
        remember(categoryPrefs, customCategories) {
            LiveViewCategories.optionsOf(categoryPrefs, customCategories)
        }
    )
    val sensitivitySetting by container.settingsRepository.autoTriggerSensitivityFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_SENSITIVITY
    )
    val paceSetting by container.settingsRepository.liveViewDetectorPaceFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_LIVEVIEW_PACE
    )
    val paceState = rememberUpdatedState(remember(paceSetting) { paceOf(paceSetting) })
    val rescanSetting by container.settingsRepository.liveViewRescanEagernessFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_LIVEVIEW_RESCAN
    )
    val rescanState = rememberUpdatedState(remember(rescanSetting) { eagernessOf(rescanSetting) })
    val styleSetting by container.settingsRepository.liveViewResultStyleFlow.collectAsStateWithLifecycle(
        initialValue = VisionSettingsRepository.DEFAULT_LIVEVIEW_STYLE
    )
    val styleState = rememberUpdatedState(styleSetting)
    // The frame a frozen-style reading was made from, kept unrecycled for as long as it is shown.
    var frozenFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val frozenActiveState = rememberUpdatedState(frozenFrame != null)
    val sensitivityState = rememberUpdatedState(
        when (sensitivitySetting) {
            "low" -> DocumentCropper.DetectionSensitivity.LOW
            "high" -> DocumentCropper.DetectionSensitivity.HIGH
            else -> DocumentCropper.DetectionSensitivity.MEDIUM
        }
    )

    // First run, the onboarding owns the permission ask — its Start button is the moment the
    // person expects the system dialog. Every later open asks straight away as usual.
    LaunchedEffect(onboarded) {
        if (onboarded == true && !cameraGranted) requestCameraPermission { cameraGranted = it }
    }

    fun unfreeze() {
        frozenFrame = null
        reading = null
        anchorNow = null
    }

    // The frozen backdrop is recycled one frame AFTER the state lets go of it — a recycle inside
    // unfreeze() itself can pull the bitmap out from under the draw that composed against it.
    var lastFrozen by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(frozenFrame) {
        val previous = lastFrozen
        lastFrozen = frozenFrame
        if (previous != null && previous !== frozenFrame) {
            androidx.compose.runtime.withFrameNanos { }
            previous.recycle()
        }
    }

    // While the frozen table is up, back means "back to the camera", not "leave the app" — this
    // handler sits inside the double-back one and wins only while enabled.
    androidx.activity.compose.BackHandler(enabled = frozenFrame != null) { unfreeze() }

    // The engine is resolved once and handed to the analyzer through state — the analyzer callback
    // is registered once and cannot suspend, so it cannot resolve the engine itself.
    var engine by remember { mutableStateOf<OcrEngine?>(null) }
    val engineState = rememberUpdatedState(engine)
    val engineReadyState = rememberUpdatedState(engineReady)
    val readInFlightState = rememberUpdatedState(readInFlight)
    val readingState = rememberUpdatedState(reading)

    LaunchedEffect(cameraGranted) {
        if (cameraGranted) {
            try {
                engine = container.ocrEngineForZone(currentZoneOrDefault(container))
                withContext(Dispatchers.IO) { DocumentCropper.init(context) }
                engineReady = true
                Logger.d(TAG, "Engine pre-warm succeeded")
            } catch (t: Throwable) {
                Logger.e(TAG, "Engine pre-warm failed", t)
            }
        }
    }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            // Analysis only: this screen never takes a picture. Same forced resolution as the scan
            // screen — the default 640x480 is as much too coarse for text as it was for contours.
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisResolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        ANALYSIS_RESOLUTION,
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(analysisExecutor) {
        onDispose { analysisExecutor.shutdown() }
    }

    LaunchedEffect(cameraController) {
        val lastAnalysisAt = longArrayOf(0L)
        val lastReadAt = longArrayOf(0L)
        // When the current framing — bounds, or the steady absence of bounds a close-up produces —
        // started holding still. 0 means it is not holding.
        val steadySinceMs = longArrayOf(0L)
        var lastRawBounds: DocumentCropper.LiveBounds? = null
        var consecutiveMisses = 0
        var agreeStreak = 0
        var lastDrawnCandidate: DocumentCropper.LiveBounds? = null
        // Main-thread only, and the chips' own bookkeeping — deliberately separate from the drawn
        // rectangle's short grace: the rectangle may blink without the reading blinking with it.
        var chipMissTicks = 0
        var chipForeignTicks = 0
        var boundsAppearedTicks = 0
        val mainExecutor = ContextCompat.getMainExecutor(context)
        cameraController.setImageAnalysisAnalyzer(analysisExecutor) { image ->
            val now = System.currentTimeMillis()
            val pace = paceState.value
            val effectiveIntervalMs =
                ((if (DocumentCropper.isMlDetectorLoaded()) ML_ANALYSIS_INTERVAL_MS else ANALYSIS_INTERVAL_MS) *
                    pace.intervalMultiplier).toLong()
            if (!engineReadyState.value || readInFlightState.value || frozenActiveState.value ||
                now - lastAnalysisAt[0] < effectiveIntervalMs
            ) {
                image.close()
                return@setImageAnalysisAnalyzer
            }
            lastAnalysisAt[0] = now

            val aspect = if (fixedRotationDegrees == 90 || fixedRotationDegrees == 270) {
                image.height.toFloat() / image.width.toFloat()
            } else {
                image.width.toFloat() / image.height.toFloat()
            }

            // Whether this tick might have to read: decided before the frame is closed, because the
            // color conversion needs the open frame. Slightly eager — stability is only known after
            // detection — but the conversion is the same cost the ML detector already pays per tick.
            val current = readingState.value
            val chipsWanted = current == null ||
                (current.chips.isEmpty() && now - current.readAtMs >= EMPTY_RETRY_MS)
            val readDue = chipsWanted && now - lastReadAt[0] >= MIN_READ_GAP_MS
            val colorBitmap = if (DocumentCropper.isMlDetectorLoaded() || readDue) {
                try {
                    yuvImageProxyToColorBitmap(image, fixedRotationDegrees)
                } catch (t: Throwable) {
                    Logger.e(TAG, "Live color-bitmap conversion failed", t)
                    null
                }
            } else {
                null
            }

            val bounds = try {
                runBlocking {
                    nativeCvLock.withLock {
                        yPlaneToGrayMat(image, fixedRotationDegrees).let { mat ->
                            try {
                                DocumentCropper.detectLiveBounds(
                                    mat,
                                    sensitivityState.value,
                                    colorBitmap.takeIf { DocumentCropper.isMlDetectorLoaded() }
                                )
                            } finally {
                                mat.release()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "Framing analysis failed", t)
                null
            } finally {
                image.close()
            }

            // Steadiness, on the analysis thread's own locals: bounds agreeing with the last tick's
            // — or staying absent, which is what a filled-frame close-up looks like — keeps the
            // clock running; anything else restarts it.
            val agrees = if (bounds != null && lastRawBounds != null) {
                boundsClose(bounds, lastRawBounds!!)
            } else {
                bounds == null && lastRawBounds == null
            }
            if (!agrees || steadySinceMs[0] == 0L) steadySinceMs[0] = now
            lastRawBounds = bounds
            val steadyForMs = now - steadySinceMs[0]


            val shouldRead = readDue && colorBitmap != null &&
                steadyForMs >= STABLE_BEFORE_READ_MS && engineState.value != null

            if (shouldRead) {
                mainExecutor.execute { readInFlight = true }
                val lines = try {
                    // Fetched per read, not the pre-warm reference: after a zone switch the
                    // container has released that engine, and this lookup hands back the current
                    // one (or builds it) instead of reading through a dead instance.
                    runBlocking {
                        val eng = container.ocrEngineForZone(currentZoneOrDefault(container))
                        nativeCvLock.withLock { eng.read(colorBitmap!!) }
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "Live read failed", t)
                    emptyList()
                }
                lastReadAt[0] = System.currentTimeMillis()
                val w = colorBitmap!!.width.toFloat()
                val h = colorBitmap.height.toFloat()
                fun boxOf(line: OcrEngine.OcrLine) =
                    DocumentCropper.LiveBounds(line.left / w, line.top / h, line.right / w, line.bottom / h)
                val entities = lines.map { LineEntities.classify(it.text, country, classifyOptionsState.value) }
                // An address absorbs the generic line(s) printed under it — the city and postal
                // code belong to the street above them, and the rows arrive in reading order, so
                // "directly under" is simply "next". See LineEntities.looksLikeAddressContinuation.
                val chips = mutableListOf<LiveChip>()
                var index = 0
                while (index < lines.size) {
                    val entity = entities[index]
                    if (entity == null) {
                        index++
                        continue
                    }
                    var box = boxOf(lines[index])
                    var merged: LineEntities.Entity = entity
                    if (entity.kind == LineEntities.Kind.ADDRESS) {
                        var absorbed = 0
                        while (absorbed < 2 && index + 1 < lines.size) {
                            val next = entities[index + 1]
                            val continues = next?.kind == LineEntities.Kind.GENERIC &&
                                LineEntities.looksLikeAddressContinuation(lines[index + 1].text)
                            if (!continues) break
                            val nextBox = boxOf(lines[index + 1])
                            merged = merged.copy(
                                value = merged.value.trimEnd(',', ' ') + ", " + lines[index + 1].text.trim()
                            )
                            box = DocumentCropper.LiveBounds(
                                minOf(box.left, nextBox.left),
                                minOf(box.top, nextBox.top),
                                maxOf(box.right, nextBox.right),
                                maxOf(box.bottom, nextBox.bottom)
                            )
                            index++
                            absorbed++
                        }
                    }
                    chips += LiveChip(merged, box)
                    index++
                }
                // The document's own domain completes its national phone numbers — the site
                // first, the email's domain second, a fixed ccTLD table, no model anywhere. Apps
                // like WhatsApp accept only the full international form, so the completed value
                // is what every action fires with; the frozen table shows the added prefix in
                // green so the correction stays visible as one.
                val tld = chips.firstOrNull { it.entity.kind == LineEntities.Kind.URL }?.entity?.value?.let(CountryDialing::tldOf)
                    ?: chips.firstOrNull { it.entity.kind == LineEntities.Kind.EMAIL }?.entity?.value?.let(CountryDialing::tldOf)
                val dial = CountryDialing.dialOf(tld)
                if (dial != null) {
                    for (i in chips.indices) {
                        val chip = chips[i]
                        if (chip.entity.kind != LineEntities.Kind.PHONE) continue
                        val completed = CountryDialing.internationalize(chip.entity.value, dial) ?: continue
                        chips[i] = chip.copy(
                            entity = chip.entity.copy(value = completed.full),
                            addedPrefix = completed.prefix
                        )
                    }
                }
                Logger.d(TAG, "Live read: ${lines.size} lines, ${chips.size} chips")
                val anchorAtRead = bounds
                val freeze = styleState.value == "frozen" && chips.isNotEmpty()
                mainExecutor.execute {
                    reading = LiveReading(chips, anchorAtRead, System.currentTimeMillis())
                    anchorNow = anchorAtRead
                    if (freeze) frozenFrame = colorBitmap
                    readInFlight = false
                }
                if (freeze) {
                    // The bitmap lives on as the frozen backdrop; the unfreeze recycles it.
                    return@setImageAnalysisAnalyzer
                }
            }
            colorBitmap?.recycle()

            mainExecutor.execute {
                val corrected = bounds?.let { remapForPreviewCrop(it, aspect, previewBoxSize) }
                analysisAspect = aspect
                if (corrected == null) {
                    consecutiveMisses++
                    agreeStreak = 0
                    lastDrawnCandidate = null
                    if (consecutiveMisses >= LIVE_BOUNDS_MISS_GRACE) liveBounds = null
                } else {
                    consecutiveMisses = 0
                    // The drawn box moves only once enough consecutive detections agree — the
                    // scan screen's anti-teleport gate, with the pace choosing how many "enough"
                    // is. First-ever detection still shows immediately, so framing feedback does
                    // not feel dead on arrival.
                    val candidate = lastDrawnCandidate
                    agreeStreak = if (candidate != null && boundsClose(corrected, candidate)) agreeStreak + 1 else 1
                    lastDrawnCandidate = corrected
                    if (liveBounds == null || agreeStreak >= pace.agreeTicks) {
                        liveBounds = corrected
                    }
                }

                // Chip upkeep. A finished reading stays for as long as its document stays. Only
                // rectangles that plausibly ARE the tracked document move its anchor — a stray
                // blob across the frame neither drags the chips nor kills them — and clearing
                // demands sustained evidence at the pace the person chose: many detection-less
                // ticks (out of frame), or many consecutive foreign rectangles (replaced).
                // Close-up readings with no anchor can notice only one change, a document
                // rectangle arriving where there was none, plus plain age.
                val held = reading ?: return@execute
                val eagerness = rescanState.value
                if (held.anchor != null) {
                    val tracked = anchorNow
                    when {
                        bounds == null -> {
                            chipMissTicks++
                            chipForeignTicks = 0
                        }
                        tracked == null || boundsWithin(bounds, tracked, SAME_DOCUMENT_TOLERANCE) -> {
                            anchorNow = bounds
                            chipMissTicks = 0
                            chipForeignTicks = 0
                        }
                        else -> {
                            chipForeignTicks++
                            chipMissTicks = 0
                        }
                    }
                    val outOfFrame = chipMissTicks >= eagerness.missGraceTicks
                    val replaced = chipForeignTicks >= eagerness.foreignTicks
                    if (outOfFrame || replaced) {
                        reading = null
                        anchorNow = null
                        chipMissTicks = 0
                        chipForeignTicks = 0
                    }
                } else {
                    boundsAppearedTicks = if (corrected != null) boundsAppearedTicks + 1 else 0
                    val sceneChanged = boundsAppearedTicks >= eagerness.appearTicks
                    val stale = now - held.readAtMs > eagerness.unanchoredStaleMs
                    if (sceneChanged || stale) {
                        reading = null
                        boundsAppearedTicks = 0
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(languageManager.getString("liveview_title")) },
                actions = {
                    IconButton(onClick = {
                        torchOn = !torchOn
                        cameraController.enableTorch(torchOn)
                    }) {
                        Icon(
                            if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = languageManager.getString("liveview_torch")
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = languageManager.getString("settings"))
                    }
                }
            )
        }
    ) { padding ->
        if (onboarded == false) {
            LiveViewOnboarding(
                modifier = Modifier.fillMaxSize().padding(padding),
                onStart = {
                    scope.launch { container.settingsRepository.setLiveViewOnboarded(true) }
                    if (!cameraGranted) requestCameraPermission { cameraGranted = it }
                }
            )
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (onboarded == null) return@Column
            if (!cameraGranted) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        languageManager.getString("camera_permission_required"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp)
                    )
                }
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .onSizeChanged { previewBoxSize = it }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            // Same two deliberate choices as the scan screen, for the same
                            // on-device reasons: SurfaceView rendering, and FIT_CENTER so
                            // remapForPreviewCrop's letterbox math holds.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            controller = cameraController
                            cameraController.bindToLifecycle(lifecycleOwner)
                        }
                    }
                )

                val bounds = liveBounds
                if (bounds != null && (showFrame || readInFlight)) {
                    val animSpec = tween<Float>(paceState.value.glideMs)
                    val animatedLeft by androidx.compose.animation.core.animateFloatAsState(bounds.left, animSpec, label = "lvLeft")
                    val animatedTop by androidx.compose.animation.core.animateFloatAsState(bounds.top, animSpec, label = "lvTop")
                    val animatedRight by androidx.compose.animation.core.animateFloatAsState(bounds.right, animSpec, label = "lvRight")
                    val animatedBottom by androidx.compose.animation.core.animateFloatAsState(bounds.bottom, animSpec, label = "lvBottom")
                    // While a read holds the frame, the rectangle breathes instead of freezing — a
                    // still overlay over a still preview reads as a hang; an animated one reads as
                    // the processing time it actually is.
                    val pulse = rememberInfiniteTransition(label = "lvPulse")
                    val glow by pulse.animateFloat(
                        initialValue = 0.10f,
                        targetValue = if (readInFlight) 0.40f else 0.10f,
                        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
                        label = "lvGlow"
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val topLeft = Offset(animatedLeft * size.width, animatedTop * size.height)
                        val boxSize = Size(
                            (animatedRight - animatedLeft) * size.width,
                            (animatedBottom - animatedTop) * size.height
                        )
                        if (readInFlight) {
                            drawRect(color = Color(0xFF00E676).copy(alpha = glow), topLeft = topLeft, size = boxSize)
                        }
                        if (showFrame) {
                            drawRect(
                                color = Color(0xFF00E676),
                                topLeft = topLeft,
                                size = boxSize,
                                style = Stroke(width = 4.dp.toPx())
                            )
                        }
                    }
                }

                val held = reading
                if (held != null && frozenFrame == null) {
                    val density = LocalDensity.current
                    val filled = styleSetting == "filled"
                    held.chips.forEach { chip ->
                        // The affine map that carried the anchor from read time to now carries
                        // every chip with it: panning translates them, moving closer scales them.
                        val tracked = transformThroughAnchor(chip.box, held.anchor, anchorNow)
                        val mapped = remapForPreviewCrop(tracked, analysisAspect, previewBoxSize) ?: return@forEach
                        val boxW = previewBoxSize.width.toFloat()
                        val boxH = previewBoxSize.height.toFloat()
                        if (boxW <= 0f || boxH <= 0f) return@forEach
                        val topPx = mapped.top * boxH
                        val heightPx = ((mapped.bottom - mapped.top) * boxH).coerceAtLeast(1f)
                        val leftPx = (mapped.left * boxW - 4).coerceAtLeast(0f)
                        val widthPx = ((mapped.right - mapped.left) * boxW + 8).coerceAtMost(boxW - leftPx)
                        if (widthPx <= 0f) return@forEach
                        val accent = chipColor(chip.entity.kind)

                        // The anchor over the text itself. Live style only points — a thin
                        // outline, the camera's own text stays legible. Filled style paints the
                        // box and writes the *recognized* text into it: what was read, readable,
                        // exactly where it was read from.
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .offset { IntOffset(leftPx.toInt(), topPx.toInt()) }
                                .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (filled) accent.copy(alpha = 0.88f) else Color.Transparent)
                                .border(1.5.dp, accent.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        ) {
                            if (filled) {
                                Text(
                                    chip.entity.value,
                                    color = Color.Black.copy(alpha = 0.9f),
                                    fontSize = with(density) { (heightPx * 0.5f).toSp() },
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }

                        // The float: the kind's baked-in action first, then one chip per app the
                        // person added — anchored at the outline's top-right, or under its
                        // bottom-right when the text sits too close to the top edge.
                        val floatApps = appsFor(chip.entity, categoryPrefs, customCategories)
                        val chipCount = 1 + floatApps.size
                        val chipPx = with(density) { 30.dp.toPx() }
                        val gapPx = with(density) { 4.dp.toPx() }
                        val rowWidthPx = chipCount * chipPx + (chipCount - 1) * gapPx
                        val rowLeftPx = (leftPx + widthPx - rowWidthPx).coerceAtLeast(0f)
                        val above = topPx - chipPx - gapPx
                        val rowTopPx = if (above >= 0f) above else topPx + heightPx + gapPx
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.offset { IntOffset(rowLeftPx.toInt(), rowTopPx.toInt()) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(accent.copy(alpha = 0.9f))
                                    .clickable { fireBaked(chip.entity, context, clipboard, languageManager) }
                            ) {
                                Icon(
                                    bakedIcon(chip.entity.kind),
                                    contentDescription = null,
                                    tint = Color.Black.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            floatApps.forEach { pkg ->
                                val icon = rememberAppIcon(pkg)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.92f))
                                        .clickable { fireApp(chip.entity, pkg, context) }
                                ) {
                                    if (icon != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = icon,
                                            contentDescription = pkg,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = pkg,
                                            tint = Color.Black.copy(alpha = 0.7f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if ((held == null || held.chips.isEmpty()) && !readInFlight && frozenFrame == null) {
                    Text(
                        languageManager.getString("liveview_hint"),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // The frozen style: the read frame stands still, darkened and washed out, and the
                // fields come forward as a table — one large row per reading, its float at the
                // end, nothing chasing the camera while the person aims their tap.
                val frozen = frozenFrame
                if (frozen != null && held != null) {
                    androidx.compose.foundation.Image(
                        bitmap = remember(frozen) { frozen.asImageBitmap() },
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(12.dp)
                    ) {
                        // The table reads by usefulness, not by layout: what you act on first
                        // comes first, and the search rows — possible on any line at all — close
                        // the list. Reading order survives inside each group.
                        held.chips.sortedBy { tableRank(it.entity.kind) }.forEach { chip ->
                            val accent = chipColor(chip.entity.kind)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                                    .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    bakedIcon(chip.entity.kind),
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    chip.entity.customName?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = accent)
                                    }
                                    val prefix = chip.addedPrefix
                                    Text(
                                        if (prefix != null && chip.entity.value.startsWith(prefix)) {
                                            androidx.compose.ui.text.buildAnnotatedString {
                                                withStyle(
                                                    androidx.compose.ui.text.SpanStyle(
                                                        color = Color(0xFF00A651),
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                                    )
                                                ) { append(prefix) }
                                                append(" ")
                                                append(chip.entity.value.removePrefix(prefix))
                                            }
                                        } else {
                                            AnnotatedString(chip.entity.value)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(accent.copy(alpha = 0.9f))
                                        .clickable { fireBaked(chip.entity, context, clipboard, languageManager) }
                                ) {
                                    Icon(
                                        bakedIcon(chip.entity.kind),
                                        contentDescription = null,
                                        tint = Color.Black.copy(alpha = 0.85f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                appsFor(chip.entity, categoryPrefs, customCategories).forEach { pkg ->
                                    val icon = rememberAppIcon(pkg)
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.95f))
                                            .clickable { fireApp(chip.entity, pkg, context) }
                                    ) {
                                        if (icon != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = icon,
                                                contentDescription = pkg,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = pkg,
                                                tint = Color.Black.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Retry reads the scene again — the same clearing back does, and with the
                    // style still frozen the next successful read freezes itself; Close is the
                    // same thing the back key does.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    ) {
                        androidx.compose.material3.FloatingActionButton(onClick = { unfreeze() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = languageManager.getString("liveview_retry")
                            )
                        }
                        androidx.compose.material3.FloatingActionButton(onClick = { unfreeze() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = languageManager.getString("liveview_unfreeze")
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The apps the person added to [entity]'s category — the float after the baked-in chip. */
private fun appsFor(
    entity: LineEntities.Entity,
    prefs: Map<LineEntities.Kind, LiveViewCategories.Prefs>,
    custom: List<LiveViewCategories.Custom>
): List<String> = when (entity.kind) {
    LineEntities.Kind.CUSTOM -> custom.firstOrNull { it.name == entity.customName }?.apps.orEmpty()
    else -> prefs[entity.kind]?.apps.orEmpty()
}

/** The baked-in chip accepted: every kind's one fixed action, through the system default. */
private fun fireBaked(
    entity: LineEntities.Entity,
    context: android.content.Context,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    languageManager: com.voxapps.vision.domain.localization.LanguageManager
) {
    when (entity.kind) {
        LineEntities.Kind.PHONE -> EntityActions.dial(context, entity.value)
        LineEntities.Kind.EMAIL -> EntityActions.composeEmail(context, entity.value)
        LineEntities.Kind.URL -> EntityActions.openUrl(context, entity.value)
        LineEntities.Kind.ADDRESS -> openLocationInMaps(context, entity.value)
        LineEntities.Kind.GENERIC -> EntityActions.searchWeb(context, entity.value)
        LineEntities.Kind.ACCOUNT, LineEntities.Kind.CUSTOM -> {
            clipboard.setText(AnnotatedString(entity.value))
            Toast.makeText(context, languageManager.getString("liveview_copied"), Toast.LENGTH_SHORT).show()
        }
    }
}

/** An added app's chip accepted: the kind's most specific carrier that app takes, shared text as
 *  the last resort — see the *ToApp family in EntityActions. */
private fun fireApp(entity: LineEntities.Entity, packageName: String, context: android.content.Context) {
    when (entity.kind) {
        LineEntities.Kind.PHONE -> EntityActions.phoneToApp(context, entity.value, packageName)
        LineEntities.Kind.EMAIL -> EntityActions.emailToApp(context, entity.value, packageName)
        LineEntities.Kind.URL -> EntityActions.urlToApp(context, entity.value, packageName)
        LineEntities.Kind.ADDRESS -> EntityActions.placeToApp(context, entity.value, packageName)
        LineEntities.Kind.ACCOUNT,
        LineEntities.Kind.CUSTOM,
        LineEntities.Kind.GENERIC -> EntityActions.textToApp(context, entity.value, packageName)
    }
}

/** The frozen table's row order: the specific, actionable kinds first, search-anything last. */
private fun tableRank(kind: LineEntities.Kind): Int = when (kind) {
    LineEntities.Kind.PHONE -> 0
    LineEntities.Kind.EMAIL -> 1
    LineEntities.Kind.URL -> 2
    LineEntities.Kind.ADDRESS -> 3
    LineEntities.Kind.ACCOUNT -> 4
    LineEntities.Kind.CUSTOM -> 5
    LineEntities.Kind.GENERIC -> 6
}

private fun bakedIcon(kind: LineEntities.Kind) = when (kind) {
    LineEntities.Kind.PHONE -> Icons.Filled.Call
    LineEntities.Kind.EMAIL -> Icons.Filled.Email
    LineEntities.Kind.URL -> Icons.Filled.Language
    LineEntities.Kind.ADDRESS -> Icons.Filled.Place
    LineEntities.Kind.GENERIC -> Icons.Filled.Search
    LineEntities.Kind.ACCOUNT, LineEntities.Kind.CUSTOM -> Icons.Filled.ContentCopy
}

/** Whether [a] and [b] sit within [tolerance] on every edge — [boundsClose] with the caller's own
 *  yardstick instead of the drawing gate's. */
private fun boundsWithin(a: DocumentCropper.LiveBounds, b: DocumentCropper.LiveBounds, tolerance: Float): Boolean =
    kotlin.math.abs(a.left - b.left) < tolerance &&
        kotlin.math.abs(a.top - b.top) < tolerance &&
        kotlin.math.abs(a.right - b.right) < tolerance &&
        kotlin.math.abs(a.bottom - b.bottom) < tolerance

/** [box] carried through the map that took [readAnchor] to [anchorNow] — per-axis scale plus
 *  translation, which is what a camera panned or moved closer does to everything in its frame.
 *  Either anchor missing means no motion estimate, and the box stands where it was read. */
private fun transformThroughAnchor(
    box: DocumentCropper.LiveBounds,
    readAnchor: DocumentCropper.LiveBounds?,
    anchorNow: DocumentCropper.LiveBounds?
): DocumentCropper.LiveBounds {
    if (readAnchor == null || anchorNow == null) return box
    val w1 = readAnchor.right - readAnchor.left
    val h1 = readAnchor.bottom - readAnchor.top
    if (w1 <= 0.01f || h1 <= 0.01f) return box
    val sx = (anchorNow.right - anchorNow.left) / w1
    val sy = (anchorNow.bottom - anchorNow.top) / h1
    fun mx(x: Float) = anchorNow.left + (x - readAnchor.left) * sx
    fun my(y: Float) = anchorNow.top + (y - readAnchor.top) * sy
    return DocumentCropper.LiveBounds(mx(box.left), my(box.top), mx(box.right), my(box.bottom))
}

/** The app's own icon for its chip, or null when the package cannot be drawn. */
@Composable
private fun rememberAppIcon(packageName: String): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching<androidx.compose.ui.graphics.ImageBitmap> {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * The first-open explainer: what this screen is, in three lines, and the one permission it needs.
 * Short by intent — the screen itself is the tutorial once the chips appear.
 */
@Composable
private fun LiveViewOnboarding(modifier: Modifier = Modifier, onStart: () -> Unit) {
    val languageManager = LocalLanguageManager.current
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(languageManager.getString("liveview_onb_title"), style = MaterialTheme.typography.headlineSmall)
        Text(languageManager.getString("liveview_onb_1"), style = MaterialTheme.typography.bodyLarge)
        Text(languageManager.getString("liveview_onb_2"), style = MaterialTheme.typography.bodyLarge)
        Text(languageManager.getString("liveview_onb_3"), style = MaterialTheme.typography.bodyLarge)
        Text(
            languageManager.getString("liveview_onb_permission"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onStart) { Text(languageManager.getString("liveview_onb_start")) }
    }
}

private fun chipColor(kind: LineEntities.Kind): Color = when (kind) {
    LineEntities.Kind.PHONE -> Color(0xFF00E676)
    LineEntities.Kind.EMAIL -> Color(0xFF40C4FF)
    LineEntities.Kind.URL -> Color(0xFF7C4DFF)
    LineEntities.Kind.ADDRESS -> Color(0xFFFFAB40)
    LineEntities.Kind.ACCOUNT -> Color(0xFF1DE9B6)
    LineEntities.Kind.CUSTOM -> Color(0xFFF06292)
    LineEntities.Kind.GENERIC -> Color(0xFFB0BEC5)
}

