package com.voxcommander.app.ui.components

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.domain.voice.TtsManager
import com.voxcommander.app.domain.voice.VoiceManager
import com.voxcommander.app.state.AppStateManager
import com.voxcommander.app.state.VoiceState
import com.voxcommander.app.ui.screens.main.ListeningScreen
import com.voxcommander.app.ui.screens.main.SpeakingOverlay
import com.voxcommander.app.ui.theme.VoxCommanderTheme
import com.voxcommander.app.utils.Logger

/**
 * Manages a system-level overlay for the listening UI.
 * This allows the microphone to appear over ANY app, including BottomSheets and Home screen.
 */
class VoiceOverlayManager(
    private val context: Context,
    private val languageManager: LanguageManager,
    private val appStateManager: AppStateManager
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null

    fun show() {
        if (composeView != null) {
            Logger.log("Overlay already shown, skipping", "VoiceOverlayManager")
            return
        }
        Logger.log("Overlay show() called", "VoiceOverlayManager")

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            // Allows the window to extend outside of the screen if needed
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            
            // Handle display cutouts for Android 9+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = ComposeView(context).apply {
            setContent {
                VoxCommanderTheme {
                    val stopAll = {
                        VoiceManager.stopListening()
                        TtsManager.stop()
                        appStateManager.setVoiceState(VoiceState.IDLE)
                    }
                    // Listening overlay (shown when VoiceManager is listening)
                    ListeningScreen(
                        languageManager = languageManager,
                        appStateManager = appStateManager,
                        onStop = stopAll
                    )
                    // Speaking overlay (shown when TtsManager is speaking)
                    SpeakingOverlay(
                        languageManager = languageManager,
                        onStop = stopAll
                    )
                }
            }
        }

        // --- CRITICAL: Add Lifecycle support to ComposeView in WindowManager ---
        val lifecycleOwner = object : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
            private val lifecycleRegistry = LifecycleRegistry(this)
            private val store = ViewModelStore()
            private val savedStateRegistryController = SavedStateRegistryController.create(this)

            override val lifecycle: Lifecycle get() = lifecycleRegistry
            override val viewModelStore: ViewModelStore get() = store
            override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

            fun onCreate() {
                savedStateRegistryController.performRestore(null)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }

            fun onDestroy() {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }

        lifecycleOwner.onCreate()
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        try {
            windowManager.addView(view, params)
            composeView = view
            Logger.log("Overlay view added to WindowManager successfully", "VoiceOverlayManager")
        } catch (e: Exception) {
            Logger.log("Failed to add overlay view: ${e.message}", "VoiceOverlayManager")
        }
    }

    fun hide() {
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // View might already be removed
            }
            composeView = null
        }
    }
}
