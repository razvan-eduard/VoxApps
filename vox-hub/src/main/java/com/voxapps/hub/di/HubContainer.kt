package com.voxapps.hub.di

import android.content.Context
import com.voxapps.hub.data.preferences.HubSettingsRepository
import com.voxapps.hub.data.preferences.HubSettingsRepositoryImpl
import com.voxapps.hub.domain.localization.LanguageManager
import com.voxapps.hub.domain.sync.SyncPeerStore
import com.voxapps.voxconnect.PairedDeviceStore
import com.voxapps.voxconnect.VoxConnectPairing
import com.voxapps.voxconnect.VoxConnectServer
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

    val voxConnectPairing: VoxConnectPairing by lazy { VoxConnectPairing() }
    val voxConnectDeviceStore: PairedDeviceStore by lazy { PairedDeviceStore(appContext) }

    /** Start/stop is driven by [HubSettings.voxConnectEnabled] — see `HubApplication.onCreate()`,
     *  which mirrors the same settings-flow-collect-and-react pattern already used to live-sync the
     *  debug-logging flags. */
    val voxConnectServer: VoxConnectServer by lazy {
        VoxConnectServer(
            context = appContext,
            deviceStore = voxConnectDeviceStore,
            allowedDomains = {
                settingsRepository.getSnapshot().voxConnectMonitoredApps
                    .filterValues { it }.keys
            },
            mediaControlEnabled = { settingsRepository.getSnapshot().voxConnectMediaControlEnabled }
        )
    }
}
