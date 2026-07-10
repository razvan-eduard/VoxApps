package com.voxapps.vision.di

import android.content.Context
import com.voxapps.vision.data.preferences.VisionSettingsRepository
import com.voxapps.vision.domain.OcrModelRegistry
import com.voxapps.vision.domain.localization.LanguageManager
import com.voxapps.vision.ocr.OcrEngine
import com.voxapps.vision.ocr.VisionModelDownloader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Manual DI container for Vox Vision (mirrors vox-notes' NotesContainer / vox-commander's
 * AppContainer shape). Owns the OCR model registry/downloader and lazily (re)builds [OcrEngine]
 * against whichever zone is currently active on disk.
 */
class VisionContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = VisionSettingsRepository(appContext)
    val ocrModelRegistry = OcrModelRegistry(appContext)
    val modelDownloader = VisionModelDownloader(appContext, ocrModelRegistry)

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(Locale.getDefault().language)
    }

    private val engineMutex = Mutex()
    private var engine: OcrEngine? = null

    /** Ensures [zone]'s models are on disk and returns a ready [OcrEngine] for them. */
    suspend fun ocrEngineForZone(zone: String): OcrEngine = engineMutex.withLock {
        modelDownloader.ensureDetModel()
        if (!modelDownloader.isReady(zone)) {
            modelDownloader.switchZone(zone)
        }
        engine?.let { return@withLock it }
        OcrEngine.create(appContext, modelDownloader).also { engine = it }
    }

    /** Call when the user switches zones — downloads the new zone and discards the old engine. */
    suspend fun switchZone(newZone: String) = engineMutex.withLock {
        modelDownloader.ensureDetModel()
        modelDownloader.switchZone(newZone)
        engine?.release()
        engine = null
        settingsRepository.setOcrZone(newZone)
    }
}
