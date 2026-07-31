package com.voxapps.vision.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
        val AUTO_TRIGGER_STABILITY = stringPreferencesKey("auto_trigger_stability")
        val FLASH_MODE = stringPreferencesKey("flash_mode")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val DEBUG_TOASTS_ENABLED = booleanPreferencesKey("debug_toasts_enabled")
        val SEND_PHOTO_TO_AI = booleanPreferencesKey("send_photo_to_ai")
        val PHOTO_DETAIL_FOR_AI = stringPreferencesKey("photo_detail_for_ai")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
        val THEME_COLORED = booleanPreferencesKey("theme_colored")
    }

    companion object {
        const val DEFAULT_ZONE = "latin"
        const val DEFAULT_SENSITIVITY = "medium"
        const val DEFAULT_STABILITY = "medium"
        const val DEFAULT_FLASH = "auto"
        const val DEFAULT_PHOTO_DETAIL = "medium"

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
     * "a document is framed" (contour size threshold); this controls how many CONSECUTIVE good frames
     * are required before auto-capture actually fires (see [com.voxapps.vision.ui.captureStabilityTicks]).
     * Low = fires fast but may capture a slightly blurred/not-yet-settled crop; High = waits longer for
     * a well-defined crop, better OCR quality at the cost of a slower capture.
     */
    val autoTriggerStabilityFlow: Flow<String> =
        dataStore.data.map { it[Keys.AUTO_TRIGGER_STABILITY] ?: DEFAULT_STABILITY }

    suspend fun setAutoTriggerStability(stability: String) {
        dataStore.edit { it[Keys.AUTO_TRIGGER_STABILITY] = stability }
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
}
