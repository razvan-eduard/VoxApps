package com.voxapps.commander.data.remote

import androidx.compose.runtime.Immutable

import com.google.gson.Gson
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.NetworkMonitor
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.URL
import android.content.Context

/**
 * Models.json Schema Objects - The Wrapper
 */
data class RemoteModelSchema(
    val schema_version: Int,
    val prompts: Map<String, String>? = null,
    val engines: Map<String, RemoteEngineConfig>
)

data class RemoteEngineConfig(
    val engine_label: String? = null,
    val type: List<String> = emptyList(),
    val is_multilingual: Boolean,
    val extension: String = "",
    val is_default_wake_word: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val models: List<RemoteModelItem>
)

/**
 * The Unified Model Item. Implements AppModel directly.
 */
@Immutable
data class RemoteModelItem(
    override val id: String,
    override val label: String,
    val path: String,
    val size_mb: Int,
    val size_label: String? = null,
    val is_multilingual: Boolean? = null,
    val lang_code: String? = null,
    val engine_type: String? = null,
    val is_remote: Boolean = false
) : AppModel {
    override val url: String get() = path
    override val sizeDescription: String get() = size_label ?: "$size_mb MB"
    override val engineType: String get() = engine_type ?: ""
    override val langCode: String? get() = lang_code
    override val isBuiltIn: Boolean get() = !is_remote
}

/**
 * Represents a virtual model that doesn't exist as a downloadable file (e.g. Cloud APIs).
 */
data class VirtualModelItem(
    override val id: String,
    override val label: String,
    override val engineType: String,
    override val sizeDescription: String = "Cloud API",
    override val url: String = "",
    override val langCode: String? = null,
    override val isBuiltIn: Boolean = true
) : AppModel

/**
 * Orchestrator for Dynamic Model Registration.
 * Single Source of Truth for all available models across all engines.
 * Acts as a ModelManagementParser: fetch -> cache -> wrapper -> Reactive Map.
 */
object RemoteModelRegistry {
    private const val TAG = Strings.Tags.REMOTE_MODEL_REGISTRY
    private val gson = Gson()
    
    // The Wrapper Object (The SSOT in memory)
    private var cachedSchema: RemoteModelSchema? = null

    // Reactive signal that the registry has updated
    private val _registryUpdateSignal = MutableStateFlow(0L)
    val registryUpdateSignal: StateFlow<Long> = _registryUpdateSignal.asStateFlow()

    // Centralized model map: EngineName -> List<AppModel>
    private val _modelMap = MutableStateFlow<Map<String, List<AppModel>>>(emptyMap())
    val modelMap: StateFlow<Map<String, List<AppModel>>> = _modelMap.asStateFlow()

    private var appContext: Context? = null

    // Reactive load status for splash screen
    enum class LoadStatus { LOADING, LOADED_FROM_REMOTE, LOADED_FROM_CACHE, NO_NETWORK }
    private val _loadStatus = MutableStateFlow(LoadStatus.LOADING)
    val loadStatus: StateFlow<LoadStatus> = _loadStatus.asStateFlow()

    private const val LOCAL_FILE_NAME = "models.json"

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun fetchJson(repo: SettingsRepository, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        Logger.log("fetchJson called (force=$force)", TAG)

        // 1. Load from filesDir if available (immediate availability, preserves hot-reloaded data)
        if (cachedSchema == null) {
            loadFromFilesDir()
        }

        // 1b. If still no data, copy from assets (bundled APK may be newer than CDN cache)
        if (cachedSchema == null) {
            ensureLocalFile()
            loadFromFilesDir()
        }

        // 2. If not force and we have data, return early
        if (!force && cachedSchema != null) {
            _loadStatus.value = LoadStatus.LOADED_FROM_CACHE
            return@withContext true
        }

        _loadStatus.value = LoadStatus.LOADING

        // 3. Try remote fetch (repo → filesDir)
        val baseUrl = repo.getSettingsSnapshot().modelRepoBaseUrl
        val rawUrlBase = if (baseUrl.contains("github.com") && !baseUrl.contains("raw.githubusercontent.com")) {
            baseUrl.replace("github.com", "raw.githubusercontent.com").removeSuffix("/") + "/main/models.json"
        } else {
            if (baseUrl.endsWith("/")) "${baseUrl}models.json" else "$baseUrl/models.json"
        }

        val rawUrl = "$rawUrlBase?t=${System.currentTimeMillis()}"
        Logger.log("Fetching remote registry from: $rawUrl", TAG)

        return@withContext try {
            val jsonText = URL(rawUrl).readText()
            Logger.log("Network fetch success. Size: ${jsonText.length} chars", TAG)
            val schema = gson.fromJson(jsonText, RemoteModelSchema::class.java)
            if (schema != null) {
                // Don't downgrade — compare remote with assets (bundled in APK, always newest)
                val assetVersion = getAssetSchemaVersion()
                if (assetVersion > schema.schema_version) {
                    Logger.log("Remote schema v${schema.schema_version} < assets v$assetVersion, using assets (no downgrade)", TAG)
                    ensureLocalFile()
                    loadFromFilesDir()
                    _loadStatus.value = if (cachedSchema != null) LoadStatus.LOADED_FROM_CACHE else LoadStatus.NO_NETWORK
                    return@withContext cachedSchema != null
                }
                saveLocalFile(jsonText)
                cachedSchema = schema
                Logger.log("Remote JSON parsed and saved locally. Engines found: ${schema.engines.keys}", TAG)
                repo.saveModelsJsonCache(jsonText)
                rebuildModelMap()
                _registryUpdateSignal.value++
                _loadStatus.value = LoadStatus.LOADED_FROM_REMOTE
                true
            } else {
                Logger.log("Failed to parse remote JSON (schema is null)", TAG)
                _loadStatus.value = if (cachedSchema != null) LoadStatus.LOADED_FROM_CACHE else LoadStatus.NO_NETWORK
                cachedSchema != null
            }
        } catch (e: Exception) {
            Logger.log("Network fetch failed: ${e.message}. Falling back to assets.", TAG)
            // 4. No net — copy from assets if newer (assets → filesDir), then reload
            ensureLocalFile()
            loadFromFilesDir()
            _loadStatus.value = if (cachedSchema != null) LoadStatus.LOADED_FROM_CACHE else LoadStatus.NO_NETWORK
            cachedSchema != null
        }
    }

    private fun getAssetSchemaVersion(): Int {
        val ctx = appContext ?: return 0
        return try {
            ctx.assets.open(LOCAL_FILE_NAME).use { input ->
                val text = input.readBytes().decodeToString()
                gson.fromJson(text, RemoteModelSchema::class.java)?.schema_version ?: 0
            }
        } catch (e: Exception) { 0 }
    }

    /**
     * Copies models.json from bundled assets to filesDir if local is missing
     * or assets has a newer schema_version. Called as fallback when repo download fails.
     */
    private fun ensureLocalFile() {
        val ctx = appContext ?: return
        val localFile = java.io.File(ctx.filesDir, LOCAL_FILE_NAME)

        val assetText = try {
            ctx.assets.open(LOCAL_FILE_NAME).use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            Logger.log("Failed to read models.json from assets: ${e.message}", TAG)
            return
        }

        val assetVersion = try {
            gson.fromJson(assetText, RemoteModelSchema::class.java)?.schema_version ?: 0
        } catch (e: Exception) { 0 }

        val localVersion = if (localFile.exists()) {
            try {
                gson.fromJson(localFile.readText(), RemoteModelSchema::class.java)?.schema_version ?: 0
            } catch (e: Exception) { 0 }
        } else 0

        if (!localFile.exists() || assetVersion > localVersion) {
            try {
                localFile.writeText(assetText)
                Logger.log("Copied models.json from assets to filesDir (asset v$assetVersion > local v$localVersion)", TAG)
            } catch (e: Exception) {
                Logger.log("Failed to copy models.json from assets: ${e.message}", TAG)
            }
        }
    }

    /**
     * Loads and parses models.json from filesDir (the writable local copy).
     */
    private fun loadFromFilesDir() {
        val ctx = appContext ?: return
        val localFile = java.io.File(ctx.filesDir, LOCAL_FILE_NAME)
        if (!localFile.exists()) {
            ensureLocalFile()
            if (!localFile.exists()) return
        }
        try {
            val jsonText = localFile.readText()
            cachedSchema = gson.fromJson(jsonText, RemoteModelSchema::class.java)
            if (cachedSchema != null) {
                Logger.log("Loaded models.json from filesDir. Engines: ${cachedSchema?.engines?.keys}", TAG)
                rebuildModelMap()
            }
        } catch (e: Exception) {
            Logger.log("Failed to parse local models.json: ${e.message}. Overwriting from assets.", TAG)
            try {
                ctx.assets.open(LOCAL_FILE_NAME).use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                }
                val freshText = localFile.readText()
                cachedSchema = gson.fromJson(freshText, RemoteModelSchema::class.java)
                if (cachedSchema != null) {
                    Logger.log("Recovered models.json from assets. Engines: ${cachedSchema?.engines?.keys}", TAG)
                    rebuildModelMap()
                }
            } catch (e2: Exception) {
                Logger.log("Failed to recover models.json from assets: ${e2.message}", TAG)
            }
        }
    }

    /**
     * Saves remote JSON text to filesDir, overwriting the local copy.
     */
    private fun saveLocalFile(jsonText: String) {
        val ctx = appContext ?: return
        try {
            java.io.File(ctx.filesDir, LOCAL_FILE_NAME).writeText(jsonText)
            Logger.log("Saved updated models.json to filesDir", TAG)
        } catch (e: Exception) {
            Logger.log("Failed to save models.json to filesDir: ${e.message}", TAG)
        }
    }

    /**
     * Rebuilds the memory map from the current cached schema.
     */
    private fun rebuildModelMap() {
        val schema = cachedSchema ?: return
        val newMap = mutableMapOf<String, MutableList<AppModel>>()
        
        Logger.log("rebuildModelMap starting...", TAG)
        
        // Ingest from JSON and inject the key as engine_type
        schema.engines.forEach { (key, config) ->
            Logger.log("Ingesting engine: $key (type=${config.type}, models=${config.models.size})", TAG)
            val models = config.models.map { it.copy(engine_type = key) }.toMutableList<AppModel>()

            newMap[key] = models
        }

        Logger.log("Final modelMap keys: ${newMap.keys}", TAG)
        _modelMap.value = newMap
    }

    fun getEngineTypes(): List<String> = cachedSchema?.engines?.keys?.toList() ?: emptyList()

    /**
     * Triggers a rebuild of the model map (e.g. after custom model import).
     * Re-scans filesDir for custom models and injects them into the map.
     */
    fun refreshModelMap() {
        rebuildModelMap()
        _registryUpdateSignal.value++
    }

    fun getEngineKeysByType(type: String): List<String> {
        val result = cachedSchema?.engines?.filter { type in it.value.type }?.keys?.toList() ?: emptyList()
        Logger.log("getEngineKeysByType(type=$type) -> $result", TAG)
        return result
    }

    fun getEngineLabel(engineKey: String, languageManager: LanguageManager): String {
        val config = cachedSchema?.engines?.get(engineKey)
        if (config?.engine_label != null) return config.engine_label
        
        // Local/Virtual fallbacks
        return when (engineKey) {
            Strings.Processors.GOOGLE -> languageManager.getString("engine_label_google")
            Strings.Processors.WHISPER_API -> languageManager.getString("engine_label_whisper_api")
            Strings.Processors.WHISPER_VULKAN -> languageManager.getString("engine_label_vulkan_experimental")
            Strings.AiProcessors.OPENAI -> languageManager.getString("engine_label_openai_gpt")
            Strings.AiProcessors.GEMINI_NATIVE -> languageManager.getString("engine_label_gemini_nano")
            Strings.AiProcessors.GEMINI_CLOUD -> languageManager.getString("engine_label_gemini_cloud")
            else -> engineKey.replace("_", " ").uppercase()
        }
    }

    fun getModels(engineKey: String): List<AppModel> {
        return _modelMap.value[engineKey] ?: emptyList()
    }

    fun getExtension(engineKey: String): String = cachedSchema?.engines?.get(engineKey)?.extension ?: ""

    fun getEngineTypes(engineKey: String): List<String> = cachedSchema?.engines?.get(engineKey)?.type ?: emptyList()

    fun getEngineType(engineKey: String): String? = getEngineTypes(engineKey).firstOrNull()

    fun isZipEngine(engineKey: String): Boolean = getExtension(engineKey).equals(".zip", ignoreCase = true)

    /** True for any on-device local LLM engine (declared via the "local_llm" capability in
     *  models.json, not the "llm" type) — there can be more than one (e.g. one per model file
     *  format: .task vs .litertlm), each independently selectable as the user's AI processor. */
    fun isLlmEngine(engineKey: String): Boolean = hasCapability(engineKey, "local_llm")

    /** Every engine key declaring the "local_llm" capability — the dynamic equivalent of the old
     *  getEngineKeysByType("llm") lookup, but driven by capability rather than type so a new local
     *  LLM engine (a different model format, a different runtime) needs zero code changes here. */
    fun getLlmEngineKeys(): List<String> =
        cachedSchema?.engines?.filter { hasCapability(it.key, "local_llm") }?.keys?.toList() ?: emptyList()

    fun isWakeWordEngine(engineKey: String): Boolean = "wake_word" in getEngineTypes(engineKey)

    fun isVoiceEngine(engineKey: String): Boolean = "voice" in getEngineTypes(engineKey)

    fun getEngineKeyByExtension(ext: String): String? {
        return cachedSchema?.engines?.entries
            ?.firstOrNull { it.value.extension.equals(ext, ignoreCase = true) }?.key
    }

    fun getDefaultVoiceEngineKey(): String? {
        return getEngineKeysByType("voice").firstOrNull()
    }

    fun getDefaultLlmEngineKey(): String? {
        return getLlmEngineKeys().firstOrNull()
    }

    fun isMultilingual(engineKey: String): Boolean = cachedSchema?.engines?.get(engineKey)?.is_multilingual ?: false

    fun hasCapability(engineKey: String, capability: String): Boolean {
        return capability in (cachedSchema?.engines?.get(engineKey)?.capabilities ?: emptyList())
    }

    /**
     * Whether [processor] (an `aiProcessor` setting value — either a hardcoded cloud constant from
     * [Strings.AiProcessors] or a `models.json`-defined local engine key) accepts image input. Mirrors
     * the same hardcoded-cloud-first, JSON-fallback duality [LlmHookEngineSelector]/[IntentDecisionMap]
     * already use to *select* an engine, rather than inventing a new lookup shape for capability.
     */
    fun isMultimodal(processor: String): Boolean =
        processor in Strings.AiProcessors.MULTIMODAL_CAPABLE || hasCapability(processor, "multimodal")

    /**
     * Whether [processor] runs on-device rather than calling out to a cloud API — mirrors
     * [isMultimodal]'s hardcoded-cloud-first check, just inverted: only the fixed
     * [Strings.AiProcessors.CLOUD_PROCESSORS] set leaves the device, so anything not in it (Gemini
     * Nano, or any `models.json`-defined downloaded engine key) is local by elimination.
     */
    fun isLocalEngine(processor: String): Boolean = processor !in Strings.AiProcessors.CLOUD_PROCESSORS

    fun getDefaultWakeWordEngineKey(): String {
        return cachedSchema?.engines?.entries
            ?.firstOrNull { it.value.is_default_wake_word }?.key
            ?: "wake_vosk"
    }

    fun getLanguages(engineKey: String): List<String> {
        if (isMultilingual(engineKey)) return emptyList()
        return getModels(engineKey)
            .mapNotNull { it.langCode }
            .distinct()
            .sorted()
    }

    fun getPrompt(id: String): String? = cachedSchema?.prompts?.get(id)

    fun getModelMapNow(): Map<String, List<AppModel>> {
        return _modelMap.value
    }

    /**
     * Resolves the final download URL.
     */
    fun resolveUrl(item: AppModel, repo: SettingsRepository): String {
        if (item.url.startsWith("http")) return item.url
        val baseUrl = repo.getSettingsSnapshot().modelRepoBaseUrl
        return if (baseUrl.contains("github.com")) {
            val cleanBase = baseUrl.removeSuffix("/")
            "$cleanBase/releases/download/${item.url}"
        } else {
            val cleanBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val cleanPath = if (item.url.startsWith("/")) item.url.substring(1) else item.url
            "$cleanBase$cleanPath"
        }
    }
}
