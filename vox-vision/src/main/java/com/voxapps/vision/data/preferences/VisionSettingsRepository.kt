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
    }

    companion object {
        const val DEFAULT_ZONE = "latin"
        const val DEFAULT_SENSITIVITY = "medium"
        const val DEFAULT_STABILITY = "medium"
        const val DEFAULT_FLASH = "auto"
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
}
