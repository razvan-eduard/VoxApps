package com.voxapps.hub.di

import android.content.Context
import com.voxapps.hub.domain.localization.LanguageManager
import java.util.Locale

/**
 * Manual DI container for Vox Hub (mirrors vox-vision's VisionContainer shape). Hub holds no local
 * database or settings — it's a pure IPC client over [com.voxapps.ipc.VoxAppsDiscovery] /
 * [com.voxapps.ipc.VoxDataTransferClient], so there's nothing else to wire here yet.
 */
class HubContainer(context: Context) {
    private val appContext = context.applicationContext

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(Locale.getDefault().language)
    }
}
