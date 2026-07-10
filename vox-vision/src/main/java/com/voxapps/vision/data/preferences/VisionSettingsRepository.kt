package com.voxapps.vision.data.preferences

import android.content.Context
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
    }

    companion object {
        const val DEFAULT_ZONE = "latin"
        const val DEFAULT_SENSITIVITY = "medium"
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
}
