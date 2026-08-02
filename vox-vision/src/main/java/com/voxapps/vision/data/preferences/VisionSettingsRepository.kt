package com.voxapps.vision.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persisted Vision settings. Deliberately not split into a full interface/impl pair like Notes'
 * settings — Vision has very few settings today.
 */
class VisionSettingsRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val OCR_ZONE = stringPreferencesKey("ocr_zone")
        val AUTO_TRIGGER_SENSITIVITY = stringPreferencesKey("auto_trigger_sensitivity")
        val AUTO_CAPTURE_DELAY_SECONDS = intPreferencesKey("auto_capture_delay_seconds")
        val FLASH_MODE = stringPreferencesKey("flash_mode")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val DEBUG_TOASTS_ENABLED = booleanPreferencesKey("debug_toasts_enabled")
        val SEND_PHOTO_TO_AI = booleanPreferencesKey("send_photo_to_ai")
        val PHOTO_DETAIL_FOR_AI = stringPreferencesKey("photo_detail_for_ai")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
        val STITCH_CONTINUITY_STRICTNESS = stringPreferencesKey("stitch_continuity_strictness")
    }

    companion object {
        const val DEFAULT_ZONE = "latin"
        const val DEFAULT_SENSITIVITY = "medium"
        /** Sentinel for [autoCaptureDelaySecondsFlow] — auto-capture never fires, only the manual
         *  capture button does. */
        const val AUTO_CAPTURE_MANUAL = 0
        const val DEFAULT_AUTO_CAPTURE_DELAY = 2
        const val DEFAULT_FLASH = "auto"
        const val DEFAULT_PHOTO_DETAIL = "medium"
        // LAZY (not medium) is the out-of-the-box default — the realistic stitch shot is one short
        // receipt line at a time, and LAZY's lower overlap-ratio requirement (vs. medium/strict) gives
        // more headroom for OCR noise or a partial (not whole-line) overlap between shots. See
        // com.voxapps.vision.ocr.ContinuityMatcher's Strictness doc comments for the full reasoning.
        const val DEFAULT_STITCH_STRICTNESS = "lazy"

        // Same "SYSTEM"/"LIGHT"/"DARK" string encoding as com.voxapps.design.VoxDarkMode.name — kept
        // as plain strings here (rather than importing the enum) to match CalendarSettings'/
        // HubSettings' data-layer convention of not depending on core:design's types directly.
        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"

        /**
         * Long-edge target pixels for each [photoDetailForAiFlow] level — the only thing that
         * actually reduces LLM token cost for an attached photo (OpenAI/Gemini tokenize images by
         * pixel-dimension tiling; JPEG compression quality and color depth don't factor in, only
         * resolution does). Bounded so "Low" still comfortably fits a receipt's line-item text —
         * going lower would save a little more but risks making the photo useless to the model,
         * which defeats the point of attaching it at all.
         */
        fun targetLongEdgePx(detail: String): Int = when (detail) {
            "high" -> 1536
            "low" -> 768
            else -> 1024 // "medium"
        }
    }

    val ocrZoneFlow: Flow<String> = dataStore.data.map { it[Keys.OCR_ZONE] ?: DEFAULT_ZONE }

    suspend fun setOcrZone(zone: String) {
        dataStore.edit { it[Keys.OCR_ZONE] = zone }
    }

    /**
     * How eagerly the live camera preview auto-triggers a capture once it thinks a document is
     * framed (see [com.voxapps.vision.ocr.DocumentCropper.DetectionSensitivity]) — does not affect the
     * final auto-crop step run on the captured photo, which always uses its own fixed, stricter
     * threshold regardless of this setting.
     */
    val autoTriggerSensitivityFlow: Flow<String> =
        dataStore.data.map { it[Keys.AUTO_TRIGGER_SENSITIVITY] ?: DEFAULT_SENSITIVITY }

    suspend fun setAutoTriggerSensitivity(sensitivity: String) {
        dataStore.edit { it[Keys.AUTO_TRIGGER_SENSITIVITY] = sensitivity }
    }

    /**
     * Separate from [autoTriggerSensitivityFlow] — that controls how easily a SINGLE frame counts as
     * "a document is framed" (contour size threshold) at all; this controls what happens once a
     * document has been framed: [AUTO_CAPTURE_MANUAL] means capture never fires on its own (only the
     * manual capture button does), any other value is the number of seconds the framing must hold
     * before auto-capture fires on its own — see `com.voxapps.vision.ui.VisionScreen`'s
     * `LaunchedEffect(cameraController)`. Replaces the old implicit tick-count "capture speed" setting
     * with an explicit, user-facing delay.
     */
    val autoCaptureDelaySecondsFlow: Flow<Int> =
        dataStore.data.map { it[Keys.AUTO_CAPTURE_DELAY_SECONDS] ?: DEFAULT_AUTO_CAPTURE_DELAY }

    suspend fun setAutoCaptureDelaySeconds(seconds: Int) {
        dataStore.edit { it[Keys.AUTO_CAPTURE_DELAY_SECONDS] = seconds }
    }

    val flashModeFlow: Flow<String> = dataStore.data.map { it[Keys.FLASH_MODE] ?: DEFAULT_FLASH }

    suspend fun setFlashMode(mode: String) {
        dataStore.edit { it[Keys.FLASH_MODE] = mode }
    }

    /** Gates `com.voxapps.logging.Logger` output — off by default so logcat isn't flooded. */
    val debugLoggingEnabledFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.DEBUG_LOGGING_ENABLED] ?: false }

    suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_LOGGING_ENABLED] = enabled }
    }

    /** Gates `com.voxapps.logging.Logger` on-screen toasts — off by default. */
    val debugToastsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.DEBUG_TOASTS_ENABLED] ?: false }

    suspend fun setDebugToastsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_TOASTS_ENABLED] = enabled }
    }

    /**
     * Off by default — attaching a photo costs real LLM tokens on top of the (free, local) OCR text
     * this app already provides, so it's an opt-in, not an opt-out. When off, this app never prepares
     * or offers an AI-attachment copy at all (see [com.voxapps.vision.ui.captureAndRecognize]) —
     * downstream consumers (Expenses/Notes) never receive one to attach regardless of their own
     * per-satellite toggles.
     */
    val sendPhotoToAiFlow: Flow<Boolean> = dataStore.data.map { it[Keys.SEND_PHOTO_TO_AI] ?: false }

    suspend fun setSendPhotoToAi(enabled: Boolean) {
        dataStore.edit { it[Keys.SEND_PHOTO_TO_AI] = enabled }
    }

    /** "high" | "medium" | "low" — see [targetLongEdgePx] for what each maps to and why. */
    val photoDetailForAiFlow: Flow<String> =
        dataStore.data.map { it[Keys.PHOTO_DETAIL_FOR_AI] ?: DEFAULT_PHOTO_DETAIL }

    suspend fun setPhotoDetailForAi(detail: String) {
        dataStore.edit { it[Keys.PHOTO_DETAIL_FOR_AI] = detail }
    }

    val themeDarkModeFlow: Flow<String> = dataStore.data.map { it[Keys.THEME_DARK_MODE] ?: THEME_SYSTEM }

    suspend fun setThemeDarkMode(mode: String) {
        dataStore.edit { it[Keys.THEME_DARK_MODE] = mode }
    }

    val themeColoredFlow: Flow<Boolean> = dataStore.data.map { it[Keys.THEME_COLORED] ?: true }

    suspend fun setThemeColored(enabled: Boolean) {
        dataStore.edit { it[Keys.THEME_COLORED] = enabled }
    }

    /** "strict" | "medium" | "lazy" — how much word-overlap [com.voxapps.vision.ocr.ContinuityMatcher]
     *  requires between consecutive stitch shots before accepting the new one without a retake prompt.
     *  See [com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_STITCH]'s doc comment for the feature itself. */
    val stitchContinuityStrictnessFlow: Flow<String> =
        dataStore.data.map { it[Keys.STITCH_CONTINUITY_STRICTNESS] ?: DEFAULT_STITCH_STRICTNESS }

    suspend fun setStitchContinuityStrictness(strictness: String) {
        dataStore.edit { it[Keys.STITCH_CONTINUITY_STRICTNESS] = strictness }
    }
}
