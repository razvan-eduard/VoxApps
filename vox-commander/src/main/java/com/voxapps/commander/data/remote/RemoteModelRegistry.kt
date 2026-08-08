package com.voxapps.commander.data.remote

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable

import com.google.gson.Gson
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.model.AppModel
import com.voxapps.logging.Logger
import com.voxapps.services.RemoteSchema
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
 *
 * Every field defaults, so a repo serving `{}` parses into a schema with no engines rather than
 * throwing or producing nulls behind non-null types. That is what makes "point the repository at
 * something empty and get only what is compiled in" a supported configuration instead of a crash.
 */
data class RemoteModelSchema(
    val schema_version: Int = 0,
    val prompts: Map<String, String>? = null,
    val engines: Map<String, RemoteEngineConfig> = emptyMap()
)

data class RemoteEngineConfig(
    val engine_label: String? = null,
    val type: List<String> = emptyList(),
    /** Defaulted like everything else here, and this is the parameter that made the rest of them
     *  worthless: without a default on *every* parameter Kotlin emits no no-arg constructor, so
     *  Gson allocates the object without running one and each absent field arrives null — which is
     *  why [withSafeCollections] had to exist at all. Engines that say nothing about languages are
     *  the common case, not an error. */
    val is_multilingual: Boolean = false,
    val extension: String = "",
    val is_default_wake_word: Boolean = false,
    val capabilities: List<String> = emptyList(),
    /** See [EngineRuntime]. Nullable because Gson writes `null` straight through a non-null Kotlin
     *  type: an engine missing the field would then throw at an unrelated call site instead of
     *  being reported by validateSchema() at load. */
    val runtime: String? = null,
    /** See [EntryPoint]. Nullable for the same reason, and because virtual engines have no file. */
    val entry: EntryPoint? = null,
    /** How long a call to this engine may take, when the engine knows better than the user's
     *  general setting — see [com.voxapps.commander.domain.engine.CloudDeadline]. Null means the
     *  setting decides. */
    val timeout_seconds: Int? = null,
    /** The `prompts` entry this engine wants, when the one written for its runtime does not suit
     *  it. Null means the runtime's prompt, then the standard one. */
    val prompt_id: String? = null,
    /** A translations key for this engine's name, resolved before [engine_label]. Without it a
     *  schema could only ship one language's label to a four-language settings screen —
     *  [engine_label] is the fallback for whoever has no translation. */
    val label_key: String? = null,
    /** Where the user obtains a credential for this engine — shown as a link beside the key field.
     *  An engine that needs a key but says nothing about where to get one simply shows no link. */
    val api_key_url: String? = null,
    /** Where this engine's service lives. The probe below is resolved against it, and it is the
     *  only host a probe can reach. */
    val endpoint: String? = null,
    /**
     * A path that answers cheaply if this engine is reachable and its credential is accepted — a
     * models listing, not an inference. Declaring it is what gives an engine a connection test,
     * including one added to this schema by hand.
     *
     * Relative to [endpoint] (`/models`), or omitted to probe the endpoint itself. Absolute URLs are
     * refused: the credential travels with this request, and a path can only reach the host the
     * declaration already names.
     */
    val probe_url: String? = null,
    /** How the credential attaches — see [com.voxapps.services.AuthDeclaration]. */
    val auth: com.voxapps.services.AuthDeclaration? = null,
    /** A translations key for the sentence explaining how to get that credential. A key rather than
     *  the sentence itself, for the same reason as [label_key]. */
    val api_key_help_key: String? = null,
    val models: List<RemoteModelItem> = emptyList()
)

/**
 * Where an engine's loadable artefact ends up once the download has been unpacked.
 *
 * This exists because "the model" is a different shape per engine: whisper hands its library the
 * downloaded file itself, Piper hands sherpa-onnx a file *inside* the extracted directory, and Vosk
 * hands `Model(path)` the *directory* — the file it contains is only proof that the directory is the
 * right one. A single `File` path cannot describe all three without saying which of them it is.
 *
 * Declared as a marker to search for rather than a fixed relative path on purpose. Upstream archives
 * sometimes carry a wrapper directory and sometimes do not — that is the packaging choice of
 * whoever published the model, and it can change between releases of the same engine. Searching
 * absorbs that; a fixed path encodes someone else's tarball layout into our schema.
 *
 * @param self the downloaded file *is* the artefact — nothing was extracted (`.bin`, `.task`).
 * @param match glob matched against names inside the extracted directory, e.g. `*.onnx` or `am`.
 * @param target `"file"` to hand over the match itself, `"dir"` for the directory containing it.
 */
data class EntryPoint(
    val self: Boolean = false,
    val match: String? = null,
    val target: String? = null
)

/**
 * What an engine actually is, declared rather than inferred.
 *
 * Replaces classification by elimination — `isLocalEngine(p) = p !in CLOUD_PROCESSORS` against a
 * hardcoded list, which is how adding a second on-device LLM key silently broke the fallback guard:
 * the code assumed one such key and nothing flagged that the assumption had expired.
 *
 * Deliberately a dedicated field and not a capability. Capabilities are additive, so absence means
 * "no" and a missing one is indistinguishable from a deliberate one — which is the defect being
 * removed here. Exactly one runtime per engine, validated on load, makes a missing or misspelled
 * value *detectable*.
 */
enum class EngineRuntime(val key: String) {
    /** Downloadable artefact on disk; the engine loads it through [EntryPoint]. */
    LOCAL_FILE("local_file"),

    /** Answered over the network. No file, no download, needs a credential. */
    CLOUD("cloud"),

    /** On-device but supplied by an OS service, so it can be *absent* on a given device
     *  (Google STT, the platform TTS) — which is what the availability probes check today. */
    ANDROID_LOCAL("android_local"),

    /** On-device and built into the engine itself; nothing to download and nothing to resolve
     *  (Porcupine's builtin keywords). */
    DEVICE_BUILTIN("device_builtin");

    companion object {
        fun fromKey(key: String?): EngineRuntime? = entries.find { it.key == key }
    }
}

/**
 * The Unified Model Item. Implements AppModel directly.
 */
@Immutable
data class RemoteModelItem(
    override val id: String = "",
    override val label: String = "",
    /** Defaulted for the same Gson reason as the schema's own fields: a virtual engine's model, if
     *  it ever declares one, has nothing to download. */
    val path: String = "",
    val size_mb: Int = 0,
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
    override val id: String = "",
    override val label: String = "",
    override val engineType: String = "",
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

    /** Artefact extensions that arrive compressed. See [isArchiveEngine]. Exposed so a test can
     *  check the real models.json against the production list rather than restating it. */
    @VisibleForTesting
    internal val ARCHIVE_EXTENSIONS = listOf(".zip", ".tar.bz2")
    
    // The Wrapper Object (The SSOT in memory)
    /** Written only on Dispatchers.IO (fetchJson's withContext), read pervasively from Compose on
     *  Main — every engine/model lookup in Settings goes through it. */
    @Volatile private var cachedSchema: RemoteModelSchema? = null

    // Reactive signal that the registry has updated
    private val _registryUpdateSignal = MutableStateFlow(0L)
    val registryUpdateSignal: StateFlow<Long> = _registryUpdateSignal.asStateFlow()

    // Centralized model map: EngineName -> List<AppModel>
    private val _modelMap = MutableStateFlow<Map<String, List<AppModel>>>(emptyMap())
    val modelMap: StateFlow<Map<String, List<AppModel>>> = _modelMap.asStateFlow()

    /** Assigned once in init() on Main (Application.onCreate), read from Dispatchers.IO by every
     *  fetchJson path — unsafe publication otherwise, same shape as Logger.appContext. */
    @Volatile private var appContext: Context? = null

    // Reactive load status for splash screen
    enum class LoadStatus { LOADING, LOADED_FROM_REMOTE, LOADED_FROM_CACHE, NO_NETWORK }
    private val _loadStatus = MutableStateFlow(LoadStatus.LOADING)
    val loadStatus: StateFlow<LoadStatus> = _loadStatus.asStateFlow()

    private const val LOCAL_FILE_NAME = "models.json"
    private const val VIRTUAL_FILE_NAME = "virtual_models.json"

    /**
     * The engine catalogue, and the cloud engines declared beside it.
     *
     * Two files rather than one because they answer to different owners: `models.json` describes
     * what can be downloaded and run, while `virtual_models.json` describes services that need no
     * download — and a deployment may serve an empty copy of the second to say "nothing here leaves
     * the device", which is a legitimate answer rather than a broken file. Hence the different
     * [usable] rules.
     *
     * Both rebuild the same merged registry when they load, so it does not matter which arrives
     * first or whether only one of them changed.
     */
    private val virtualSchema = RemoteSchema(
        fileName = VIRTUAL_FILE_NAME,
        type = RemoteModelSchema::class.java,
        usable = { true },
        tag = TAG,
        onLoaded = { applyMerged() }
    )

    private val modelsSchema = RemoteSchema(
        fileName = LOCAL_FILE_NAME,
        type = RemoteModelSchema::class.java,
        usable = { it.engines.isNotEmpty() },
        tag = TAG,
        onLoaded = { applyMerged() }
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        // Virtual first, so the merge has both halves the moment models.json lands.
        virtualSchema.init(context)
        modelsSchema.init(context)
    }

    /**
     * Installs whatever is loaded as the registry's contents, virtual engines merged in first.
     *
     * Every path that assigns the schema goes through here, because a merge only some of them
     * perform is a registry whose contents depend on how the app happened to start. Virtual engines
     * are merged *under* the catalogue so a schema describing an engine of its own always wins over
     * the declaration of the same key.
     */
    private fun applyMerged() {
        val models = modelsSchema.value ?: return
        val virtual = virtualSchema.value?.engines.orEmpty()
        val engines = (virtual + models.engines).mapValues { it.value.withSafeCollections() }
        cachedSchema = models.copy(engines = engines)
        rebuildModelMap()
        _registryUpdateSignal.value++
        _loadStatus.value =
            if (modelsSchema.source == RemoteSchema.Source.ACCEPTED) LoadStatus.LOADED_FROM_REMOTE
            else LoadStatus.LOADED_FROM_CACHE
    }

    /**
     * Replaces the collections Gson may have left null with empty ones.
     *
     * Every parameter of [RemoteEngineConfig] now has a default, so Gson builds it through the
     * constructor and an *absent* field takes that default. This still earns its place for the
     * other half of the problem: a field written as an explicit `null` is null whichever way the
     * object was made, and a schema served from a repository can say anything at all. Normalising
     * once, where the schema is installed, means no reader has to defend itself.
     */
    /** The same normalisation [applySchema] performs, so a test can assert against a schema in the
     *  shape the app actually runs rather than the shape Gson happened to produce. */
    @VisibleForTesting
    internal fun normalised(schema: RemoteModelSchema): RemoteModelSchema =
        schema.copy(engines = schema.engines.mapValues { it.value.withSafeCollections() })

    @Suppress("SENSELESS_COMPARISON")
    private fun RemoteEngineConfig.withSafeCollections(): RemoteEngineConfig = copy(
        // Not only the collections: `extension` is a non-null String with a default, and Gson leaves
        // it null just the same when the field is absent — which then fails inside `copy` itself,
        // before anything has a chance to read it.
        extension = extension ?: "",
        type = type ?: emptyList(),
        capabilities = capabilities ?: emptyList(),
        models = models ?: emptyList()
    )

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
        validateSchema()
    }

    /**
     * Reports engines whose declaration the code cannot act on, at load time rather than at the
     * moment something needs them.
     *
     * Logs only — a schema this cannot parse is still better than no schema, and the remote copy is
     * user-configurable, so refusing to load would let a bad file brick the picker. The value is
     * that a missing or misspelled field becomes visible in one line at startup instead of
     * surfacing later as "my engine vanished from settings".
     */
    private fun validateSchema() {
        val schema = cachedSchema ?: return
        schema.engines.forEach { (key, config) ->
            if (EngineRuntime.fromKey(config.runtime) == null) {
                Logger.log(
                    "Engine '$key' has ${if (config.runtime == null) "no" else "unrecognised"} " +
                        "runtime${config.runtime?.let { " '$it'" } ?: ""} — falling back to inference",
                    TAG
                )
            }
            if (runtimeOf(key) == EngineRuntime.LOCAL_FILE && config.entry == null) {
                Logger.log("Engine '$key' is local_file but declares no entry point", TAG)
            }
        }
    }

    /**
     * The declared [EngineRuntime] for an engine, with an inference fallback for schemas written
     * before the field existed.
     *
     * The fallback is kept because the remote copy can legitimately be older than the app — a user
     * pointing [SettingsRepository]'s `modelRepoBaseUrl` at their own repo controls when it updates,
     * and a v11 schema must not classify every engine as unknown.
     */
    fun runtimeOf(engineKey: String): EngineRuntime? {
        val config = cachedSchema?.engines?.get(engineKey)
        EngineRuntime.fromKey(config?.runtime)?.let { return it }

        // Legacy inference: an engine that downloads something is a local file; a known cloud
        // processor key is cloud. Anything else stays null rather than guessing, so callers can
        // tell "old schema, inferred" from "genuinely unknown".
        if (config != null && config.extension.isNotBlank()) return EngineRuntime.LOCAL_FILE
        if (engineKey in Strings.AiProcessors.CLOUD_PROCESSORS) return EngineRuntime.CLOUD
        return null
    }

    /**
     * The call budget this engine declares for itself, or null to let the user's setting decide.
     *
     * Only the engines the schema describes can answer; the cloud processors are still built-in
     * keys rather than schema entries, so today they all fall through to the setting. That is the
     * intended resolution order either way — declaring the value is a data change, not a code one.
     */
    fun declaredTimeoutSeconds(engineKey: String): Int? =
        cachedSchema?.engines?.get(engineKey)?.timeout_seconds?.takeIf { it > 0 }

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

    /**
     * The label an engine declares in models.json, or the key itself when it declares none.
     *
     * For callers with no LanguageManager to hand. It skips the localized fallbacks that
     * [getEngineLabel] applies to the virtual processors, which is fine for engines the schema
     * actually describes — every wake-word and downloadable engine carries a label of its own.
     */
    fun declaredEngineLabel(engineKey: String): String =
        cachedSchema?.engines?.get(engineKey)?.engine_label ?: engineKey

    /**
     * The engine's name for a settings screen: its translated label, then the one written in the
     * schema, then the key made presentable.
     *
     * The translation comes first because a label in JSON can only ever be in one language, and this
     * app ships four. A `when` over engine names used to do this for the virtual engines alone,
     * which is why adding one meant editing a label table, an availability check and a list — the
     * engines now declare `label_key` and none of those exist.
     */
    fun getEngineLabel(engineKey: String, languageManager: LanguageManager): String {
        val config = cachedSchema?.engines?.get(engineKey)
        config?.label_key?.let { key ->
            val translated = languageManager.getString(key)
            if (translated.isNotBlank() && translated != key) return translated
        }
        if (config?.engine_label != null) return config.engine_label
        return when (engineKey) {
            // Not a declared engine: it is stt_whisper asked to run on the GPU. See VoiceEnginesSubTab.
            Strings.Processors.WHISPER_VULKAN -> languageManager.getString("engine_label_vulkan_experimental")
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

    /**
     * True when the engine's artefact is a compressed archive that must be extracted before the
     * model is usable — as opposed to a single file that is ready the moment the download lands.
     *
     * Every caller that acts on that distinction must ask *this*, not [isZipEngine]: the download
     * layer routes archives to a temporary directory, resolves them to an extracted directory
     * rather than a file, and has to unpack them before signalling success. Asking `.zip` alone
     * sends an archive down the ready-as-is path, where it is never extracted and then fails
     * verification because no directory exists at the resolved location.
     *
     * [ARCHIVE_EXTENSIONS] is a list because each entry needs its own decoder in ModelDownloader,
     * so a format cannot be added by data alone — but it is the single place the set is stated.
     */
    fun isArchiveEngine(engineKey: String): Boolean =
        ARCHIVE_EXTENSIONS.any { getExtension(engineKey).equals(it, ignoreCase = true) }

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

    /**
     * The engine a fresh install starts on (`SettingsRepositoryImpl` falls back to this when no
     * `voiceProcessor` is stored).
     *
     * Constrained to [EngineRuntime.LOCAL_FILE]: "first voice engine in the map" is map order, and
     * once cloud and OS-supplied engines join the registry the first one could be a cloud engine —
     * which would silently hand a brand-new install a processor that needs an API key it does not
     * have. A no-op against today's schema, where every voice engine is local.
     *
     * Falls back to any voice engine when none is local: that is a registry deliberately serving
     * only cloud engines, where a cloud default is the honest answer and an empty picker is not.
     */
    fun getDefaultVoiceEngineKey(): String? =
        getEngineKeysByType("voice").firstOrNull { runtimeOf(it) == EngineRuntime.LOCAL_FILE }
            ?: getEngineKeysByType("voice").firstOrNull()

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
    /** Declared, not listed. The hardcoded set this used to consult named the same two engines the
     *  schema now does, and could only ever be edited in lockstep with it. */
    fun isMultimodal(processor: String): Boolean = hasCapability(processor, "multimodal")

    /**
     * Whether [processor] runs on-device rather than calling out to a cloud API.
     *
     * Now answered from the engine's declared [EngineRuntime] rather than by elimination against a
     * hardcoded cloud list. The old form said "anything not known to be cloud is local", which made
     * every unrecognised key local — including a typo, and including an engine the schema simply had
     * not been updated for.
     *
     * **An unknown key now reports false.** The one caller is `CapabilityQueryReceiver`, whose reply
     * tells a satellite app whether it may hand Commander an image; "I don't know what this engine
     * is" must not read to that caller as "safe, it stays on the device".
     */
    fun isLocalEngine(processor: String): Boolean =
        when (runtimeOf(processor)) {
            EngineRuntime.LOCAL_FILE, EngineRuntime.ANDROID_LOCAL, EngineRuntime.DEVICE_BUILTIN -> true
            EngineRuntime.CLOUD -> false
            null -> false
        }

    /**
     * The declared [EntryPoint] for an engine, or null when it declares none — a virtual engine, or
     * a schema predating the field.
     */
    fun getEntryPoint(engineKey: String): EntryPoint? = cachedSchema?.engines?.get(engineKey)?.entry

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

    /** Where to obtain this engine's credential, if it says. */
    fun declaredApiKeyUrl(engineKey: String): String? =
        cachedSchema?.engines?.get(engineKey)?.api_key_url?.takeIf { it.isNotBlank() }

    /**
     * What it takes to test this engine, or null when it declares nothing to reach.
     *
     * The absence of a declaration is how "not testable" is expressed — an on-device engine has no
     * endpoint, and Porcupine needs a key that its SDK validates locally with no URL to call.
     */
    fun probeSpecFor(engineKey: String, credential: String?): com.voxapps.services.ProbeSpec? {
        val config = cachedSchema?.engines?.get(engineKey) ?: return null
        val auth = config.auth
            ?: if (hasCapability(engineKey, "requires_api_key")) {
                com.voxapps.services.AuthDeclaration(
                    style = com.voxapps.services.AuthDeclaration.STYLE_BEARER
                )
            } else null
        return com.voxapps.services.ProbeSpec.from(
            id = engineKey,
            endpoint = config.endpoint,
            probeUrl = config.probe_url,
            auth = auth?.probeStyle() ?: com.voxapps.services.ProbeSpec.AuthStyle.None,
            credential = credential
        )
    }

    /** The translations key for this engine's "how to get a key" sentence, if it declares one. */
    fun declaredApiKeyHelpKey(engineKey: String): String? =
        cachedSchema?.engines?.get(engineKey)?.api_key_help_key?.takeIf { it.isNotBlank() }

    /** The prompt this engine asks for by name, if any — see
     *  [com.voxapps.commander.domain.intent.interpreter.PromptProvider]. */
    fun declaredPromptId(engineKey: String): String? =
        cachedSchema?.engines?.get(engineKey)?.prompt_id?.takeIf { it.isNotBlank() }

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
