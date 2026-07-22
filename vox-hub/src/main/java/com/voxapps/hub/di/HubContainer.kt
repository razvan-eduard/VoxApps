package com.voxapps.hub.di

import android.content.Context
import com.voxapps.hub.data.preferences.HubSettingsRepository
import com.voxapps.hub.data.preferences.HubSettingsRepositoryImpl
import com.voxapps.hub.domain.localization.LanguageManager
import com.voxapps.hub.domain.sync.SyncPeerStore
import java.util.Locale

/**
 * Manual DI container for Vox Hub (mirrors vox-vision's VisionContainer shape). Hub holds no local
 * database — it's a pure IPC client over [com.voxapps.ipc.VoxAppsDiscovery] /
 * [com.voxapps.ipc.VoxDataTransferClient] — but does persist a small theme preference, see
 * [HubSettingsRepository].
 */
class HubContainer(context: Context) {
    private val appContext = context.applicationContext

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(Locale.getDefault().language)
    }

    val settingsRepository: HubSettingsRepository = HubSettingsRepositoryImpl(appContext)

    val syncPeerStore: SyncPeerStore by lazy { SyncPeerStore(appContext) }
}
