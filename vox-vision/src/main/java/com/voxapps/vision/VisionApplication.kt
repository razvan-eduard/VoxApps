package com.voxapps.vision

import android.app.Application
import com.voxapps.logging.Logger
import com.voxapps.vision.di.VisionContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class VisionApplication : Application() {
    lateinit var container: VisionContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = VisionContainer(this)

        // Keep the shared Logger's on/off flag in sync with the persisted Settings toggle.
        container.settingsRepository.debugLoggingEnabledFlow
            .distinctUntilChanged()
            .onEach { Logger.setEnabled(it) }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
