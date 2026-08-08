package com.voxapps.commander.ui.viewmodels

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.DownloadCompleteReceiver
import com.voxapps.commander.data.remote.EngineRuntime
import com.voxapps.commander.data.remote.ModelDownloader
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.services.SchemaCatalog
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.commander.state.AppStateManager
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for managing model downloads and cleanup.
 * Orchestrates UI state for all model types (Vosk, Whisper, Llama) directly from RemoteModelRegistry.
 */
class ModelManagementViewModel(
    private val settingsRepo: SettingsRepository,
    private val appStateManager: AppStateManager,
    private val modelDownloader: ModelDownloader,
    private val languageManager: LanguageManager,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private companion object {
        private const val TAG = Strings.Tags.MODEL_MANAGEMENT_VIEW_MODEL
    }

    // --- REACTIVE MODEL LISTS (Zero manual mapping) ---
    private val _voskModels = MutableStateFlow<List<AppModel>>(emptyList())
    val voskModels: StateFlow<List<AppModel>> = _voskModels.asStateFlow()

    private val _whisperModels = MutableStateFlow<List<AppModel>>(emptyList())
    val whisperModels: StateFlow<List<AppModel>> = _whisperModels.asStateFlow()

    private val _nluModels = MutableStateFlow<List<AppModel>>(emptyList())
    val nluModels: StateFlow<List<AppModel>> = _nluModels.asStateFlow()

    // --- OTHER UI STATES ---
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _selectionSuccessMessage = MutableStateFlow<String?>(null)
    val selectionSuccessMessage: StateFlow<String?> = _selectionSuccessMessage.asStateFlow()

    private val _isVoskLoading = MutableStateFlow(false)
    val isVoskLoading: StateFlow<Boolean> = _isVoskLoading.asStateFlow()

    private val _isVoskOffline = MutableStateFlow(false)
    val isVoskOffline: StateFlow<Boolean> = _isVoskOffline.asStateFlow()

    private val _voskError = MutableStateFlow<String?>(null)
    val voskError: StateFlow<String?> = _voskError.asStateFlow()

    private val _showVulkanError = MutableStateFlow(false)
    val showVulkanError: StateFlow<Boolean> = _showVulkanError.asStateFlow()

    fun dismissVulkanError() { _showVulkanError.value = false }

    val vulkanTestState = appStateManager.vulkanTestState
    val vulkanTestPassed = appStateManager.vulkanTestPassed

    fun dismissVulkanTestResult() { appStateManager.dismissVulkanTestResult() }

    // --- DOWNLOAD TRACKING ---
    private var progressJob: Job? = null
    private var currentDownloadId: Long? = null
    private var lastDownloadType: String? = null
    private var lastDownloadedId: String? = null

    private val _downloadingItem = MutableStateFlow<AppModel?>(null)
    val downloadingItem: StateFlow<AppModel?> = _downloadingItem.asStateFlow()

    // Neither DownloadCompleteReceiver nor this class's own progress polling used to check
    // DownloadManager's COLUMN_STATUS/COLUMN_REASON at all — a failed download (bad URL, network
    // drop, server error) looked identical to the user as one that just silently never finished:
    // the progress bar vanished with zero explanation. Confirmed on-device against a real failure
    // (HuggingFace's Xet CDN backend 403ing every Whisper model file).
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    fun clearDownloadError() { _downloadError.value = null }

    private var handledDownloadIds = mutableSetOf<Long>()

    private val onDownloadCompleteLocal = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadCompleteReceiver.EXTRA_DOWNLOAD_ID, -1) ?: -1
            val directoryName = intent?.getStringExtra("directory_name")
            val modelType = intent?.getStringExtra("model_type")

            Logger.log("Download complete broadcast received: id=$id, type=$modelType, dir=$directoryName, handled=${handledDownloadIds.contains(id)}", Strings.Tags.MODEL_MANAGEMENT_VIEW_MODEL)
            if (id != -1L && !handledDownloadIds.contains(id)) {
                handledDownloadIds.add(id)
                _downloadProgress.value = null
                _downloadingItem.value = null
                progressJob?.cancel()

                if (modelType != null) {
                    lastDownloadType = modelType
                }

                // Don't overwrite lastDownloadedId — it was set correctly in downloadModel()
                // directoryName from broadcast may have -N suffix from DownloadManager
                Logger.log("Calling handleDownloadSuccess: type=$lastDownloadType, id=$lastDownloadedId", Strings.Tags.MODEL_MANAGEMENT_VIEW_MODEL)
                handleDownloadSuccess()
            } else {
                Logger.log("Skipping download complete: id=$id, already handled or invalid", Strings.Tags.MODEL_MANAGEMENT_VIEW_MODEL)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, onDownloadCompleteLocal, IntentFilter(DownloadCompleteReceiver.ACTION_DOWNLOAD_COMPLETE_LOCAL), ContextCompat.RECEIVER_EXPORTED)
        
        // AUTO-SYNC: Rebuild all UI lists whenever the Registry Wrapper is updated
        viewModelScope.launch {
            RemoteModelRegistry.registryUpdateSignal.collectLatest { 
                rebuildUiLists()
            }
        }

        // Initial fetch
        viewModelScope.launch { loadModels(force = false) }
    }

    private fun rebuildUiLists() {
        // Dynamically resolve all engine keys from models.json
        val voiceKeys = RemoteModelRegistry.getEngineKeysByType("voice")
        val llmKeys = RemoteModelRegistry.getLlmEngineKeys()

        // These two lists drive different import flows — a single-file picker versus a directory
        // picker — so the split is by packaging, not by engine name. Both are restricted to
        // downloadable engines: a cloud voice engine has no packaging at all and would otherwise win
        // the "not an archive" test purely on map order once virtual engines join the registry.
        val downloadableVoiceKeys = voiceKeys.filter {
            RemoteModelRegistry.runtimeOf(it) == EngineRuntime.LOCAL_FILE
        }

        val whisperKey = downloadableVoiceKeys.firstOrNull { !RemoteModelRegistry.isArchiveEngine(it) }
        _whisperModels.value = whisperKey?.let { RemoteModelRegistry.getModels(it) } ?: emptyList()

        val voskKey = downloadableVoiceKeys.firstOrNull { RemoteModelRegistry.isArchiveEngine(it) }
        _voskModels.value = voskKey?.let { RemoteModelRegistry.getModels(it) } ?: emptyList()

        // NLU models = every local-LLM-capable engine's models pooled together (there can be more
        // than one — e.g. nlu_llm for .task models, nlu_llm_litertlm for .litertlm models).
        _nluModels.value = llmKeys.flatMap { RemoteModelRegistry.getModels(it) }

        _isVoskOffline.value = _voskModels.value.isEmpty()

        Logger.log("Rebuilt UI lists: ${_whisperModels.value.size} Whisper, ${_voskModels.value.size} Vosk, ${_nluModels.value.size} NLU", TAG)
    }

    suspend fun loadModels(force: Boolean = false) {
        _isVoskLoading.value = true
        // Whatever schema is in force is already loaded; asking the repository for a newer one is a
        // deliberate act with its own button, not something a list rebuild does on the way past.
        if (force) SchemaCatalog.refreshAll(settingsRepo.getSettingsSnapshot().modelRepoBaseUrl)
        rebuildUiLists()
        _isVoskLoading.value = false
    }

    suspend fun loadVoskModels(force: Boolean = false) = loadModels(force)

    // --- DOWNLOAD METHODS ---

    fun downloadModel(modelId: String, engineType: String, lang: String? = null) {
        // Prevent duplicate downloads — if already downloading, ignore
        if (_downloadingItem.value != null) {
            Logger.log("Download already in progress (${_downloadingItem.value?.id}), ignoring request for $modelId", TAG)
            return
        }
        Logger.log("downloadModel called: modelId=$modelId, engineType=$engineType, lang=$lang", TAG)
        lastDownloadedId = modelId; lastDownloadType = engineType

        val item = RemoteModelRegistry.getModels(engineType).find { it.id == modelId } ?: return
        _downloadingItem.value = item

        // Pre-flight check: if already on disk, just select it
        val localFile = modelDownloader.resolveLocalFile(modelId, engineType)
        if (localFile?.exists() == true) {
            Logger.log("Model already exists, marking as downloaded: $modelId", TAG)
            viewModelScope.launch { settingsRepo.setModelDownloaded(modelId, true) }
            // Any engine declaring the "local_llm" capability (there can be more than one — e.g.
            // nlu_llm for .task models, nlu_llm_litertlm for .litertlm models) routes through the
            // intent-model path; anything else is a voice engine. Was previously comparing against
            // only the FIRST llm-typed engine key, silently misrouting every other one into the
            // voice-model branch.
            if (RemoteModelRegistry.isLlmEngine(engineType)) {
                appStateManager.setActiveIntentModelId(modelId)
                appStateManager.saveIntentModelSelection(engineType, modelId)
            } else {
                appStateManager.setActiveVoiceModelId(modelId)
                appStateManager.saveVoiceModelSelection(engineType, modelId)
            }
            appStateManager.refreshAll()
            _downloadingItem.value = null
            return
        }

        // Start real download
        val id = modelDownloader.downloadModel(modelId, RemoteModelRegistry.resolveUrl(item, settingsRepo), engineType)
        if (id != -1L) {
            currentDownloadId = id
            startProgressTracking(id)
        }
    }

    fun selectVoiceModel(modelId: String, engineKey: String, langCode: String? = null) {
        if (langCode != null) appStateManager.setModelFilterLang(langCode)

        // Set active immediately — model can be active even if not on device
        appStateManager.setActiveVoiceModelId(modelId)
        // Save selection per engine so switching back restores it
        viewModelScope.launch { settingsRepo.setEngineModelSelection(engineKey, modelId) }

        // If already on disk, mark as downloaded
        val file = modelDownloader.resolveLocalFile(modelId, engineKey)
        if (file?.exists() == true) {
            viewModelScope.launch { settingsRepo.setModelDownloaded(modelId, true) }
        }
        // No refreshAll(): UI updates flow from uiState, and the STT engine reloads
        // reactively via VoiceManager's observer on activeVoiceModelId. Bumping
        // refreshTrigger here churned the model dropdown's `groups` (a re-fire loop).
    }

    /**
     * Stores a model the user picked themselves.
     *
     * One import for every engine, performed where models are kept. The two shapes differ only in
     * what gets copied — a file, or a directory and everything in it — and ModelDownloader decides
     * that from the engine's declared packaging, the same way it decides where a download lands.
     *
     * The directory case used to copy nothing at all: it stored `uri.path` of the picked *tree*,
     * which is a document-id string rather than a filesystem path, so the import reported success
     * and left behind a value nothing could open.
     */
    fun selectCustomModel(uri: Uri, engineKey: String, langCode: String? = null) {
        val imported = modelDownloader.importCustomModel(uri, engineKey, langCode)
        if (imported == null) {
            Logger.log("Custom model import failed for $engineKey", TAG)
            _downloadError.value = languageManager.getString("error_download_failed")
            appStateManager.refreshAll()
            return
        }

        viewModelScope.launch { settingsRepo.setCustomModelPath(engineKey, imported.absolutePath, langCode) }
        showSuccessMessage(
            languageManager.getString(
                if (isSingleFileEngine(engineKey)) "success_custom_whisper" else "success_custom_vosk"
            )
        )
        appStateManager.refreshAll()
    }

    /**
     * Whether a custom import for [engineKey] is one file to copy, as opposed to a directory to
     * reference where it lies.
     *
     * Both halves matter. An archive engine has an extension but loads from a directory, so testing
     * the extension alone sent it down the file path — which is why importing a custom Vosk model
     * did nothing. An engine with no extension has no single file to copy either, so it belongs on
     * the directory path as well.
     */
    private fun isSingleFileEngine(engineKey: String): Boolean =
        !RemoteModelRegistry.isArchiveEngine(engineKey) &&
            RemoteModelRegistry.getExtension(engineKey).isNotBlank()

    fun cancelDownload() {
        currentDownloadId?.let { id ->
            val engineKey = lastDownloadType ?: return@let
            val modelId = lastDownloadedId ?: return@let
            
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.remove(id)
            progressJob?.cancel()
            _downloadProgress.value = null
            
            modelDownloader.resolveLocalFile(modelId, engineKey)?.let { file ->
                if (file.exists()) file.deleteRecursively()
            }
            
            _downloadingItem.value = null
            appStateManager.refreshAll()
            showSuccessMessage(languageManager.getString("error_download_failed"))
        }
    }

    fun clearDefaultOfflineFallback() { viewModelScope.launch { settingsRepo.clearDefaultOfflineFallback() }; appStateManager.refreshAll() }

    fun clearCustomModel(engineKey: String, langCode: String? = null) {
        viewModelScope.launch {
            settingsRepo.setCustomModelPath(engineKey, "", langCode)
            appStateManager.refreshAll()
        }
    }

    fun deleteUnusedModels() {
        val snapshot = settingsRepo.getSettingsSnapshot()
        val activeVoiceModelId = snapshot.activeVoiceModelId
        val activeIntentModelId = snapshot.activeIntentModelId

        val activeWakeModelId = snapshot.wakeWordModelPath?.let { java.io.File(it).name }

        viewModelScope.launch(ioDispatcher) {
            modelDownloader.deleteUnusedModels(settingsRepo, activeVoiceModelId, activeIntentModelId, appStateManager, activeWakeModelId)
            appStateManager.refreshAll()
        }
    }

    fun deleteModel(modelId: String, engineKey: String) {
        modelDownloader.deleteModelFile(modelId, engineKey)
        viewModelScope.launch { settingsRepo.setModelDownloaded(modelId, false) }

        // Deleting a model the user had chosen as a fallback used to move the checkbox onto the
        // active model — writing the *primary's* processor as the fallback processor, a value the
        // cascade skips (it requires fallback != primary) and the voice path never reads at all. The
        // choice was silently replaced by an inert one. Clearing it is the honest outcome: the
        // fallback is gone, and the settings screen shows that.
        val snapshot = settingsRepo.getSettingsSnapshot()
        if (snapshot.defaultVoiceFallbackModel == modelId) {
            viewModelScope.launch { settingsRepo.clearDefaultVoiceFallback() }
        }
        if (snapshot.defaultIntentFallbackModel == modelId) {
            viewModelScope.launch { settingsRepo.clearDefaultIntentFallback() }
        }

        appStateManager.refreshAll()
    }

    /**
     * DownloadManager.COLUMN_REASON, when COLUMN_STATUS == STATUS_FAILED, holds either a raw HTTP
     * status code (400-599, when the server actually responded) or one of the ERROR_* sentinel
     * constants (>= 1000) for client-side failures — the two ranges never overlap, so a plain
     * numeric comparison is enough to tell them apart without extra state.
     */
    private fun describeDownloadFailure(reason: Int): String = when {
        reason in 400..599 -> "server error $reason"
        reason == DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough storage space"
        reason == DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage not available"
        reason == DownloadManager.ERROR_HTTP_DATA_ERROR -> "connection dropped"
        reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        reason == DownloadManager.ERROR_CANNOT_RESUME -> "couldn't resume download"
        reason == DownloadManager.ERROR_FILE_ERROR -> "local file error"
        else -> "network error (code $reason)"
    }

    private fun startProgressTracking(id: Long) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch(ioDispatcher) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return@launch
            while (true) {
                dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total > 0) _downloadProgress.value = downloaded.toFloat() / total.toFloat()
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_FAILED) {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            val modelLabel = _downloadingItem.value?.label ?: "Model"
                            Logger.log("Download failed: id=$id, reason=$reason", TAG)
                            _downloadError.value = "$modelLabel download failed: ${describeDownloadFailure(reason)}"
                        }
                        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_PAUSED) {
                            _downloadProgress.value = null
                            _downloadingItem.value = null
                            return@launch
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun handleDownloadSuccess() {
        _downloadProgress.value = null; _downloadingItem.value = null; progressJob?.cancel()
        val modelId = lastDownloadedId ?: return
        val engineKey = lastDownloadType ?: return

        Logger.log("Download success handler. ID: $modelId, Engine: $engineKey", TAG)

        val localFile = modelDownloader.resolveLocalFile(modelId, engineKey)
        
        if (localFile?.exists() == true) {
            Logger.log("File/Dir verified on disk: ${localFile.absolutePath}", TAG)
            viewModelScope.launch {
                settingsRepo.setModelDownloaded(modelId, true)
                Logger.log("setModelDownloaded(true) completed for $modelId", TAG)
                appStateManager.refreshAll()
                rebuildUiLists()
            }
        } else {
            Logger.log("Verification failed: $modelId ($engineKey) not found at expected location", TAG)
        }

        lastDownloadType = null
    }

    private fun handleDownloadFailure() {
        showSuccessMessage(languageManager.getString("error_download_failed"))
        lastDownloadType = null
    }

    private fun showSuccessMessage(msg: String) {
        _selectionSuccessMessage.value = msg
        viewModelScope.launch { delay(5000); _selectionSuccessMessage.value = null }
    }

    override fun onCleared() {
        super.onCleared()
        context.unregisterReceiver(onDownloadCompleteLocal)
        progressJob?.cancel()
    }
}
