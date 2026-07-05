package com.voxcommander.app

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxcommander.app.data.remote.RemoteModelRegistry
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voxcommander.app.di.AppContainer
import com.voxcommander.app.domain.voice.VoiceManager
import com.voxcommander.app.ui.screens.main.MainScreen
import com.voxcommander.app.ui.screens.onboarding.LanguageSelectionScreen
import com.voxcommander.app.ui.screens.onboarding.TutorialScreen
import com.voxcommander.app.ui.screens.splash.SplashLoadingScreen
import com.voxcommander.app.ui.theme.VoxCommanderTheme
import com.voxcommander.app.ui.LocalLanguageManager
import com.voxcommander.app.service.SpotifyPkceManager
import com.voxcommander.app.domain.localization.TutorialManager
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings
import com.voxcommander.app.utils.VoiceIntentLauncher

/**
 * MainActivity: Thin UI Container.
 * Manages high-level Android lifecycle, permissions, and system intents.
 * Business logic and functional state are delegated to AppStateManager and VoiceManager.
 */
class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var voiceIntentLauncher: VoiceIntentLauncher
    private var pendingModelLanguage: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        appContainer.appStateManager.refreshPermissions()
    }

    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        appContainer.appStateManager.refreshPermissions()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        appContainer.appStateManager.refreshPermissions()
    }

    private val customVoskModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val engineKey = appContainer.appStateManager.uiState.value.voiceProcessor
            pendingModelLanguage?.let { lang ->
                appContainer.modelManagementViewModel.selectCustomModel(it, engineKey, lang)
            }
        }
    }

    private val customWhisperModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val engineKey = appContainer.appStateManager.uiState.value.voiceProcessor
            appContainer.modelManagementViewModel.selectCustomModel(it, engineKey)
        }
    }

    private val customOpenWakeWordModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val destDir = java.io.File(filesDir, "openwakeword_models")
            if (!destDir.exists()) destDir.mkdirs()
            val fileName = it.lastPathSegment?.substringAfterLast('/') ?: "custom_model.onnx"
            val destFile = java.io.File(destDir, fileName)
            try {
                contentResolver.openInputStream(it)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                Logger.log("OpenWakeWord custom model imported: ${destFile.absolutePath}", "MainActivity")
                com.voxcommander.app.data.remote.RemoteModelRegistry.refreshModelMap()
                appContainer.appStateManager.refreshAll()
            } catch (e: Exception) {
                Logger.log("Failed to import OpenWakeWord model: ${e.message}", "MainActivity")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Logger.log("MainActivity: onCreate called")

        // Application-scoped dependency container (created once, survives rotation)
        appContainer = (application as VoxApplication).container
        appContainer.languageManager.loadLanguage(appContainer.settingsRepository.getSettingsSnapshot().language)
        Logger.log("MainActivity: Language loaded: ${appContainer.settingsRepository.getSettingsSnapshot().language}")

        // Handle Spotify PKCE redirect if app was launched via deep link (cold start)
        handleSpotifyRedirect(intent)

        // Google Voice Intent launcher (lifecycle-bound, must live in the Activity)
        voiceIntentLauncher = VoiceIntentLauncher(this) { result ->
            VoiceManager.handleIntentResult(result)
        }

        // Initialize VoiceManager (reactively manages engines from AppStateManager)
        appContainer.initVoiceManager(this, voiceIntentLauncher)

        // Check Google STT availability
        val googleSttAvailable = android.speech.SpeechRecognizer.isRecognitionAvailable(this)

        checkPermissions()
        appContainer.appStateManager.refreshPermissions()

        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLanguageManager provides appContainer.languageManager) {
            VoxCommanderTheme {
                val navController = rememberNavController()
                val currentProgress by appContainer.modelManagementViewModel.downloadProgress.collectAsStateWithLifecycle()
                val successMessage by appContainer.modelManagementViewModel.selectionSuccessMessage.collectAsStateWithLifecycle()
                val showVulkanError by appContainer.modelManagementViewModel.showVulkanError.collectAsStateWithLifecycle()
                val loadStatus by RemoteModelRegistry.loadStatus.collectAsStateWithLifecycle()

                // Show splash screen while loading assets on startup
                var showSplash by remember { mutableStateOf(true) }

                // First-launch onboarding states
                val settingsSnapshot = appContainer.settingsRepository.getSettingsSnapshot()
                var showLanguageSelection by remember { mutableStateOf(false) }
                var showTutorial by remember { mutableStateOf(false) }
                val tutorialManager = remember { TutorialManager(this@MainActivity) }

                if (showSplash) {
                    SplashLoadingScreen(
                        settingsRepo = appContainer.settingsRepository,
                        onFinished = {
                            showSplash = false
                            if (!settingsSnapshot.firstLaunchCompleted) {
                                showLanguageSelection = true
                            } else if (!settingsSnapshot.tutorialCompleted) {
                                tutorialManager.load(settingsSnapshot.language)
                                showTutorial = true
                            }
                        }
                    )
                    return@VoxCommanderTheme
                }

                if (showLanguageSelection) {
                    LanguageSelectionScreen(
                        onLanguageSelected = { langCode ->
                            appContainer.languageManager.loadLanguage(langCode)
                            appContainer.appStateManager.setAppLanguage(langCode)
                            tutorialManager.load(langCode)
                            showLanguageSelection = false
                            showTutorial = true
                        }
                    )
                    return@VoxCommanderTheme
                }

                if (showTutorial) {
                    TutorialScreen(
                        tutorialManager = tutorialManager,
                        langCode = appContainer.settingsRepository.getSettingsSnapshot().language,
                        onSkip = {
                            showTutorial = false
                            appContainer.appStateManager.setFirstLaunchCompleted(true)
                            appContainer.appStateManager.setTutorialCompleted(true)
                        },
                        onFinish = {
                            showTutorial = false
                            appContainer.appStateManager.setFirstLaunchCompleted(true)
                            appContainer.appStateManager.setTutorialCompleted(true)
                        }
                    )
                    return@VoxCommanderTheme
                }

                // --- UI STATE OBSERVERS ---
                // Background trigger logic is now handled in WakeWordService for system-wide reliability.

                if (showVulkanError) {
                    AlertDialog(
                        onDismissRequest = { appContainer.modelManagementViewModel.dismissVulkanError() },
                        title = { Text(appContainer.languageManager.getString("vulkan_incompatible_title")) },
                        text = { Text(appContainer.languageManager.getString("vulkan_incompatible_msg")) },
                        confirmButton = {
                            TextButton(onClick = { appContainer.modelManagementViewModel.dismissVulkanError() }) {
                                Text(appContainer.languageManager.getString("ok"))
                            }
                        }
                    )
                }

                NavHost(navController = navController, startDestination = Strings.Routes.MAIN) {
                    composable(Strings.Routes.MAIN) {
                        MainScreen(
                            settingsRepo = appContainer.settingsRepository,
                            appStateManager = appContainer.appStateManager,
                            fastMapDao = appContainer.fastMapDao,
                            viewModel = appContainer.mainViewModel,
                            modelManagementViewModel = appContainer.modelManagementViewModel,
                            onDownloadModel = { modelId, engineType, lang ->
                                appContainer.modelManagementViewModel.downloadModel(modelId, engineType, lang)
                            },
                            onDeleteUnusedModels = {
                                appContainer.modelManagementViewModel.deleteUnusedModels()
                                Toast.makeText(this@MainActivity, appContainer.languageManager.getString("unused_models_deleted"), Toast.LENGTH_SHORT).show()
                            },
                            onCancelDownload = {
                                appContainer.modelManagementViewModel.cancelDownload()
                                Toast.makeText(this@MainActivity, appContainer.languageManager.getString("download_cancelled"), Toast.LENGTH_SHORT).show()
                            },
                            onDeleteModel = { modelId, engineKey ->
                                appContainer.modelManagementViewModel.deleteModel(modelId, engineKey)
                                Toast.makeText(this@MainActivity, "Model deleted", Toast.LENGTH_SHORT).show()
                            },
                            downloadProgress = currentProgress,
                            selectionSuccessMessage = successMessage,
                            googleSttAvailable = googleSttAvailable,
                            updateVoiceEngine = { /* Handled reactively by VoiceManager */ },
                            onRequestOverlayPermission = {
                                overlayPermissionLauncher.launch(com.voxcommander.app.utils.PermissionUtils.getOverlayPermissionIntent(this@MainActivity))
                            },
                            onRequestMicrophonePermission = {
                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onRequestNotificationPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onRequestLocationPermission = {
                                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            },
                            onImportCustomModel = { langCode ->
                                val isZipEngine = com.voxcommander.app.data.remote.RemoteModelRegistry.isZipEngine(
                                    appContainer.appStateManager.uiState.value.voiceProcessor
                                )
                                if (isZipEngine) {
                                    pendingModelLanguage = langCode
                                    customVoskModelLauncher.launch(null)
                                } else {
                                    customWhisperModelLauncher.launch(arrayOf("*/*"))
                                }
                            },
                            onClearCustomModel = {
                                val engineKey = appContainer.appStateManager.uiState.value.voiceProcessor
                                val isZipEngine = com.voxcommander.app.data.remote.RemoteModelRegistry.isZipEngine(engineKey)
                                val lang = if (isZipEngine) appContainer.appStateManager.uiState.value.modelFilterLang else null
                                appContainer.modelManagementViewModel.clearCustomModel(engineKey, lang)
                            },
                            onImportOpenWakeWordModel = {
                                customOpenWakeWordModelLauncher.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appContainer.appStateManager.refreshPermissions()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent)
    }

    private fun handleSpotifyRedirect(intent: android.content.Intent) {
        val uri = intent.data ?: return
        if (uri.scheme == "voxcommander" && uri.host == "spotify") {
            Logger.log("MainActivity: Spotify PKCE redirect received: $uri")
            SpotifyPkceManager.handleRedirect(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log("MainActivity: onDestroy called")
        VoiceManager.release() // Release all native memory and resources
    }

    private fun checkPermissions() {
        val missingPermissions = com.voxcommander.app.utils.PermissionUtils.getRequiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            multiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    companion object {
        private const val MIME_TYPE_ALL = "*/*"
    }
}
