package com.voxapps.commander

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.content.Intent
import android.provider.DocumentsContract
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
import com.voxapps.commander.data.remote.RemoteModelRegistry
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voxapps.commander.di.AppContainer
import com.voxapps.commander.domain.voice.VoiceManager
import com.voxapps.commander.service.OpenWakeWordEngine
import com.voxapps.commander.ui.screens.main.MainScreen
import com.voxapps.commander.ui.screens.onboarding.LanguageSelectionScreen
import com.voxapps.commander.ui.screens.onboarding.TutorialScreen
import com.voxapps.commander.ui.screens.splash.SplashLoadingScreen
import com.voxapps.commander.ui.theme.VoxCommanderTheme
import com.voxapps.commander.ui.LocalLanguageManager
import com.voxapps.commander.service.OAuth2Manager
import com.voxapps.commander.domain.localization.TutorialManager
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import com.voxapps.commander.utils.VoiceIntentLauncher

/**
 * MainActivity: Thin UI Container.
 * Manages high-level Android lifecycle, permissions, and system intents.
 * Business logic and functional state are delegated to AppStateManager and VoiceManager.
 */
class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var voiceIntentLauncher: VoiceIntentLauncher
    private var pendingModelLanguage: String? = null

    // Compose state hoisted here (not just read once in onCreate) so a widget tap while the
    // Activity is already running (onNewIntent, no fresh onCreate/setContent — MainActivity is
    // launchMode="singleTop") still reaches MainScreen's composition. Counter, not a plain
    // Boolean, so a second widget tap still re-triggers even though a Boolean would look
    // "unchanged". 0 = no pending request (the default, normal launch).
    private val autoStartListeningTrigger = mutableIntStateOf(0)

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

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        appContainer.appStateManager.refreshPermissions()
    }

    /**
     * Where the pickers open.
     *
     * The system picker's sort order is its own — an app can name the types it wants and the folder
     * to start in, and nothing else. Downloads is where a model just fetched from a vendor's page
     * is, so starting there is the closest thing to "newest first" that is ours to decide.
     */
    private val downloadsFolder: Uri? = runCatching {
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
    }.getOrNull()

    /** [ActivityResultContracts.OpenDocument] with a starting folder. */
    private inner class OpenDocumentAtDownloads : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).apply {
                downloadsFolder?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
    }

    /** [ActivityResultContracts.OpenDocumentTree] with a starting folder. */
    private inner class OpenDocumentTreeAtDownloads : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: android.content.Context, input: Uri?): Intent =
            super.createIntent(context, input).apply {
                downloadsFolder?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
    }

    private val customVoskModelLauncher = registerForActivityResult(
        OpenDocumentTreeAtDownloads()
    ) { uri: Uri? ->
        uri?.let {
            val engineKey = appContainer.appStateManager.uiState.value.voiceProcessor
            pendingModelLanguage?.let { lang ->
                appContainer.modelManagementViewModel.selectCustomModel(it, engineKey, lang)
            }
        }
    }

    private val customWhisperModelLauncher = registerForActivityResult(
        OpenDocumentAtDownloads()
    ) { uri: Uri? ->
        uri?.let {
            val engineKey = appContainer.appStateManager.uiState.value.voiceProcessor
            // An archive engine keeps one import per language, and this launcher now serves them
            // too — it is how the vendor's .zip is picked. Dropping the language here stored the
            // model under a key nothing looks up, so it vanished from the list it had just joined.
            val langCode = pendingModelLanguage
                ?.takeIf { com.voxapps.commander.data.remote.RemoteModelRegistry.isArchiveEngine(engineKey) }
            appContainer.modelManagementViewModel.selectCustomModel(it, engineKey, langCode)
        }
    }

    private val customOpenWakeWordModelLauncher = registerForActivityResult(
        OpenDocumentAtDownloads()
    ) { uri: Uri? ->
        // Imported the same way every other engine's model is, rather than by hand here.
        //
        // This used to copy the file into a private directory of its own and then refresh the
        // registry — which builds its list from the schema, so the file appeared in no list, could
        // not be selected, and was never loaded by anything. Going through the view model stores it
        // as this engine's custom model, which is what the picker reads, what the wake service now
        // resolves, and what "delete unused models" protects.
        uri?.let {
            appContainer.modelManagementViewModel.selectCustomModel(
                it, OpenWakeWordEngine.ENGINE_KEY, forWakeWord = true
            )
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

        // Handle OAuth redirect if app was launched via deep link (cold start)
        handleOAuthRedirect(intent)
        handleWidgetIntent(intent)

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
            val themeUi by appContainer.appStateManager.uiState.collectAsStateWithLifecycle()
            VoxCommanderTheme(
                darkMode = when (themeUi.themeDarkMode) {
                    "LIGHT" -> com.voxapps.design.VoxDarkMode.LIGHT
                    "DARK" -> com.voxapps.design.VoxDarkMode.DARK
                    else -> com.voxapps.design.VoxDarkMode.SYSTEM
                },
                colored = themeUi.themeColored
            ) {
                val navController = rememberNavController()
                val currentProgress by appContainer.modelManagementViewModel.downloadProgress.collectAsStateWithLifecycle()
                val successMessage by appContainer.modelManagementViewModel.selectionSuccessMessage.collectAsStateWithLifecycle()
                val showVulkanError by appContainer.modelManagementViewModel.showVulkanError.collectAsStateWithLifecycle()
                val loadStatus by RemoteModelRegistry.loadStatus.collectAsStateWithLifecycle()

                // Which engine is waiting on "folder or archive?", or null when nothing is asking.
                // Only the archive engines have the choice: the model can arrive either extracted,
                // as their download leaves it, or in the archive their vendor publishes.
                var importSourceChoiceFor by remember { mutableStateOf<String?>(null) }
                importSourceChoiceFor?.let { engineKey ->
                    val strings = appContainer.languageManager
                    AlertDialog(
                        onDismissRequest = { importSourceChoiceFor = null },
                        title = { Text(strings.getString("import_source_title")) },
                        text = { Text(strings.getString("import_source_body")) },
                        confirmButton = {
                            TextButton(onClick = {
                                importSourceChoiceFor = null
                                customWhisperModelLauncher.launch(
                                    RemoteModelRegistry.pickerMimeTypes(engineKey)
                                )
                            }) { Text(strings.getString("import_source_archive")) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                importSourceChoiceFor = null
                                customVoskModelLauncher.launch(null)
                            }) { Text(strings.getString("import_source_folder")) }
                        }
                    )
                }

                // Show splash screen while loading assets on startup
                var showSplash by remember { mutableStateOf(true) }

                // First-launch onboarding states
                val settingsSnapshot = appContainer.settingsRepository.getSettingsSnapshot()
                var showLanguageSelection by remember { mutableStateOf(false) }
                var showTutorial by remember { mutableStateOf(false) }
                var showPermissions by remember { mutableStateOf(false) }
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
                            showPermissions = true
                        },
                        onFinish = {
                            showTutorial = false
                            showPermissions = true
                        }
                    )
                    return@VoxCommanderTheme
                }

                if (showPermissions) {
                    // First-run permission step — request overlay/mic/notifications/location so the
                    // floating voice overlay and weather search actually work (overlay is silently
                    // hidden without SYSTEM_ALERT_WINDOW; weather queries silently fail without location).
                    // The permission launchers already call refreshPermissions() on return, so the
                    // granted status updates live.
                    com.voxapps.commander.ui.screens.onboarding.PermissionsOnboardingScreen(
                        appStateManager = appContainer.appStateManager,
                        onRequestOverlay = {
                            overlayPermissionLauncher.launch(com.voxapps.commander.utils.PermissionUtils.getOverlayPermissionIntent(this@MainActivity))
                        },
                        onRequestMicrophone = {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onRequestNotification = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onRequestLocation = {
                            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onRequestBatteryOptimization = {
                            batteryOptimizationLauncher.launch(
                                com.voxapps.commander.utils.PermissionUtils.getIgnoreBatteryOptimizationsIntent(this@MainActivity)
                            )
                        },
                        onContinue = {
                            showPermissions = false
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
                            onRequestOverlayPermission = {
                                overlayPermissionLauncher.launch(com.voxapps.commander.utils.PermissionUtils.getOverlayPermissionIntent(this@MainActivity))
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
                            onRequestBatteryOptimizationPermission = {
                                batteryOptimizationLauncher.launch(
                                    com.voxapps.commander.utils.PermissionUtils.getIgnoreBatteryOptimizationsIntent(this@MainActivity)
                                )
                            },
                            onImportCustomModel = { langCode ->
                                // An archive engine takes either the extracted folder or the archive
                                // upstream ships, so it is the one case with a choice to make; the
                                // rest take a single file, filtered to what the engine declares.
                                val proc = appContainer.appStateManager.uiState.value.voiceProcessor
                                val registry = com.voxapps.commander.data.remote.RemoteModelRegistry
                                pendingModelLanguage = langCode
                                when {
                                    registry.isArchiveEngine(proc) -> importSourceChoiceFor = proc
                                    registry.getExtension(proc).isBlank() -> customVoskModelLauncher.launch(null)
                                    else -> customWhisperModelLauncher.launch(registry.pickerMimeTypes(proc))
                                }
                            },
                            onImportOpenWakeWordModel = {
                                customOpenWakeWordModelLauncher.launch(
                                    RemoteModelRegistry.pickerMimeTypes(
                                        com.voxapps.commander.service.OpenWakeWordEngine.ENGINE_KEY
                                    )
                                )
                            },
                            autoStartListeningTrigger = autoStartListeningTrigger.intValue
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
        handleOAuthRedirect(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START_LISTENING, false) == true) {
            autoStartListeningTrigger.intValue++
        }
    }

    /**
     * Handles an OAuth redirect from either a service's own dedicated host (e.g. Spotify's
     * `voxcommander://spotify/callback`, kept so its existing manifest entry never needs to
     * change) or the shared `voxcommander://oauth/callback` every future service can register
     * once and reuse. [OAuth2Manager] resolves which service via the `state` query parameter in
     * both cases — `fallbackServiceId` only matters if `state` is ever missing.
     */
    private fun handleOAuthRedirect(intent: android.content.Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "voxcommander") return
        when (uri.host) {
            "spotify" -> {
                Logger.log("MainActivity: OAuth redirect received (spotify host): $uri")
                OAuth2Manager.handleRedirect(uri, fallbackServiceId = "spotify")
            }
            "oauth" -> {
                Logger.log("MainActivity: OAuth redirect received (shared host): $uri")
                OAuth2Manager.handleRedirect(uri)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log("MainActivity: onDestroy called")
        VoiceManager.release() // Release all native memory and resources
    }

    private fun checkPermissions() {
        val missingPermissions = com.voxapps.commander.utils.PermissionUtils.getRequiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            multiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    companion object {
        private const val MIME_TYPE_ALL = "*/*"
        /** Set by SpeakWidget's tap action — immediately start listening once MainScreen is up. */
        const val EXTRA_AUTO_START_LISTENING = "com.voxapps.commander.EXTRA_AUTO_START_LISTENING"
    }
}
