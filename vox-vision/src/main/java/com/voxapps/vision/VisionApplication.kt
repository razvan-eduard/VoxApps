package com.voxapps.vision

import android.app.Application
import com.voxapps.logging.Logger
import com.voxapps.vision.di.VisionContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class VisionApplication : Application() {
    lateinit var container: VisionContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = VisionContainer(this)

        // Keep the shared Logger in sync with the persisted debug-logging/toasts flags (previously
        // unwired here — the Settings toggles existed but never actually reached Logger).
        Logger.initialize(this, "VoxVision")
        container.settingsRepository.debugLoggingEnabledFlow
            .combine(container.settingsRepository.debugToastsEnabledFlow) { logging, toasts -> logging to toasts }
            .distinctUntilChanged()
            .onEach { (logging, toasts) ->
                Logger.setEnabled(logging)
                Logger.setToastsEnabled(toasts, this)
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
