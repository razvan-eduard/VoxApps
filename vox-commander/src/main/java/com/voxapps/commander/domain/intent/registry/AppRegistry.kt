package com.voxapps.commander.domain.intent.registry

import androidx.compose.runtime.Immutable

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.voxapps.commander.utils.fromJsonOrNull
import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Scans all installed apps via PackageManager.
 * Domains and URI templates are discovered dynamically via KnownIntents probing.
 */
object AppRegistry {

    private const val TAG = "AppRegistry"

    enum class ScanStatus { IDLE, SCANNING, DONE }

    private val _scanStatus = MutableStateFlow(ScanStatus.IDLE)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus

    object TemplateParams {
        const val QUERY = "{query}"
        const val DESTINATION = "{destination}"
        const val CONTACT = "{contact}"
        const val MESSAGE_BODY = "{message_body}"
    }

    object TemplateActions {
        const val SEARCH = "search"
        const val NAVIGATE = "navigate"
        const val SEND = "send"
    }

    @Immutable
    // Defaults throughout, so Gson reads the cache through the constructor: without them it skips
    // the constructor entirely and an absent `domains` or `uriTemplates` arrives null.
    data class AppEntry(
        val packageName: String = "",
        val displayName: String = "",
        val domains: List<String> = emptyList(),
        val uriTemplates: Map<String, String> = emptyMap(),
        val isSystemApp: Boolean = false
    )

    /** Written on Main (initFromCache at startup, init from the splash) and on Dispatchers.IO
     *  (rescanAndCache); read on Main from Compose and on IO from AppResolver. */
    @Volatile private var installedPackages: Set<String> = emptySet()
    /** Same write/read threads as installedPackages — both are single-assignment swaps of an
     *  immutable collection, so visibility is the only thing at stake. */
    @Volatile private var scannedApps: List<AppEntry> = emptyList()
    private val gson = Gson()

    fun init(context: Context, onProgress: ((current: Int, total: Int, appName: String) -> Unit)? = null) {
        _scanStatus.value = ScanStatus.SCANNING
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        installedPackages = installedApps.map { it.packageName }.toSet()
        val total = installedApps.size

        scannedApps = installedApps.mapIndexed { index, appInfo ->
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val displayName = pm.getApplicationLabel(appInfo).toString()
            onProgress?.invoke(index + 1, total, displayName)
            val (uriTemplates, domains) = KnownIntents.probeMetadata(context, appInfo.packageName)
            AppEntry(
                packageName = appInfo.packageName,
                displayName = displayName,
                domains = domains,
                uriTemplates = uriTemplates,
                isSystemApp = isSystem
            )
        }.sortedBy { it.displayName.lowercase() }

        _scanStatus.value = ScanStatus.DONE
        Logger.log("AppRegistry initialized. ${scannedApps.size} apps discovered.", TAG)
    }

    fun initFromCache(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return try {
            val rawCached = gson.fromJsonOrNull<List<AppEntry>>(json) {
                Logger.log("AppRegistry cache parse failed: ${it.message}", TAG)
            }
            // Gson deserializes via reflection, not Kotlin's constructor — a cache entry from a
            // different/older schema (missing a field Gson can't fill in) leaves that "non-null"
            // AppEntry field genuinely null at runtime despite the Kotlin type, which doesn't fail
            // here (Gson doesn't throw for a missing field) but crashes much later wherever that
            // entry's packageName/displayName is actually used — confirmed on-device: a corrupt
            // cached entry reached AppSelectorDropdown -> AppPickerEntry's constructor and crashed
            // Settings' tab navigation with a NullPointerException. Filtered out here, once, so every
            // downstream consumer of scannedApps can keep trusting AppEntry's non-null contract.
            val cached = rawCached?.filter { !it.packageName.isNullOrBlank() && it.displayName != null }
                ?: emptyList()
            if (cached.isEmpty()) return false
            scannedApps = cached
            installedPackages = cached.map { it.packageName }.toSet()
            _scanStatus.value = ScanStatus.DONE
            Logger.log("AppRegistry loaded from cache. ${scannedApps.size} apps.", TAG)
            true
        } catch (e: Exception) {
            Logger.log("AppRegistry cache parse failed: ${e.message}", TAG)
            false
        }
    }

    fun toJsonCache(): String = gson.toJson(scannedApps)

    fun rescanAndCache(context: Context, onProgress: ((current: Int, total: Int, appName: String) -> Unit)? = null): String {
        init(context, onProgress)
        return toJsonCache()
    }

    fun isInstalled(packageName: String): Boolean = installedPackages.contains(packageName)

    fun resolveByPackage(packageName: String?): AppEntry? {
        if (packageName.isNullOrBlank()) return null
        return scannedApps.firstOrNull { it.packageName == packageName && isInstalled(it.packageName) }
    }

    /**
     * Resolves an app by human-readable display name (case-insensitive).
     * Also matches common aliases (e.g. "youtube" matches "YouTube").
     */
    fun resolveByName(name: String?): AppEntry? {
        if (name.isNullOrBlank()) return null
        val lower = name.trim().lowercase()
        Logger.log("resolveByName: looking for '$lower' in ${scannedApps.size} apps", TAG)

        // Exact match on displayName (case-insensitive)
        val exact = scannedApps.firstOrNull {
            it.displayName.lowercase() == lower && isInstalled(it.packageName)
        }
        if (exact != null) {
            Logger.log("resolveByName: exact match -> ${exact.packageName}", TAG)
            return exact
        }

        // Partial match on displayName
        val partial = scannedApps.firstOrNull {
            it.displayName.lowercase().contains(lower) && isInstalled(it.packageName)
        }
        if (partial != null) {
            Logger.log("resolveByName: partial match -> ${partial.packageName} (displayName='${partial.displayName}')", TAG)
            return partial
        }

        // Match by package name suffix (e.g. "youtube" -> "com.google.android.youtube")
        val suffix = scannedApps.firstOrNull {
            it.packageName.lowercase().endsWith(".$lower") && isInstalled(it.packageName)
        }
        if (suffix != null) {
            Logger.log("resolveByName: package suffix match -> ${suffix.packageName}", TAG)
            return suffix
        }

        // Debug: log first 10 app names to see what's available
        val sample = scannedApps.take(10).joinToString(", ") { "'${it.displayName}'" }
        Logger.log("resolveByName: no match for '$lower'. Sample apps: $sample", TAG)
        return null
    }

    fun getInstalledAppsForDomain(domain: String): List<AppEntry> {
        return scannedApps.filter { it.domains.contains(domain) }
    }

    fun getDefaultAppForDomain(domain: String): AppEntry? {
        return scannedApps.firstOrNull { it.domains.contains(domain) }
    }

    fun getAllRegisteredApps(): List<AppEntry> = scannedApps

    fun allInstalledApps(): List<AppEntry> = scannedApps

    fun getAllInstalledAppEntries(context: Context): List<AppEntry> {
        if (scannedApps.isEmpty()) init(context)
        return scannedApps
    }

    /**
     * Catalog of standard Android intents we probe for per app.
     * Structured as a map: action -> list of URI variants.
     * Each variant has its own probe URI, URI template, and templateAction.
     */
    object KnownIntents {

        data class UriVariant(
            val probeUri: String?,
            val uriTemplate: String?,
            val label: String,
            val templateAction: String? = null,
            val requiresQuery: Boolean = true,
            val mimeType: String? = null
        )

        data class IntentOption(
            val action: String,
            val variant: UriVariant
        )

        val LAUNCH_VARIANT = UriVariant(null, null, "Launch app", requiresQuery = false)

        /**
         * The probe catalog is now data-driven — the source of truth is `intents.json`
         * (repo root → assets → filesDir → remote), served by [IntentCatalog]. See that
         * object for the load/hot-reload plumbing and the last-resort fallback seed.
         */
        private fun catalogVariants(): List<IntentOption> =
            IntentCatalog.getAll().map { def ->
                IntentOption(
                    def.action,
                    UriVariant(
                        probeUri = def.probeUri,
                        uriTemplate = def.uriTemplate,
                        label = def.label,
                        templateAction = def.templateAction,
                        requiresQuery = def.requiresQuery,
                        mimeType = def.mimeType
                    )
                )
            }

        /**
         * Probes which intent variants a package supports by querying PackageManager.
         * For each catalog entry, probes its URI variant separately.
         * Returns only the variants that passed the probe, plus "Launch app" fallback.
         */
        fun probeSupported(context: Context, packageName: String): List<IntentOption> {
            val pm = context.packageManager
            val result = mutableListOf<IntentOption>()

            for (option in catalogVariants()) {
                val action = option.action
                val variant = option.variant
                val supported = if (variant.probeUri != null) {
                    val probe = android.content.Intent(action).apply {
                        setPackage(packageName)
                        data = android.net.Uri.parse(variant.probeUri)
                    }
                    pm.queryIntentActivities(probe, 0).isNotEmpty()
                } else if (variant.mimeType != null) {
                    val probe = android.content.Intent(action).apply {
                        setPackage(packageName)
                        type = variant.mimeType
                    }
                    pm.queryIntentActivities(probe, 0).isNotEmpty()
                } else {
                    val probe = android.content.Intent(action).apply {
                        setPackage(packageName)
                    }
                    pm.queryIntentActivities(probe, 0).isNotEmpty()
                }
                if (supported) {
                    result.add(IntentOption(action, variant))
                }
            }

            if (pm.getLaunchIntentForPackage(packageName) != null) {
                result.add(IntentOption("", LAUNCH_VARIANT))
            }

            return result.ifEmpty { listOf(IntentOption("", LAUNCH_VARIANT)) }
        }

        /**
         * Probes a package and returns accumulated uriTemplates + domains.
         * uriTemplates: templateAction -> uriTemplate (last match wins per action).
         * domains: deduced from templateAction (navigate -> maps, search -> audio, send -> messaging).
         */
        fun probeMetadata(context: Context, packageName: String): Pair<Map<String, String>, List<String>> {
            val options = probeSupported(context, packageName)
            val uriTemplates = mutableMapOf<String, String>()
            val domains = mutableSetOf<String>()

            for (option in options) {
                val v = option.variant
                if (v.templateAction != null && v.uriTemplate != null) {
                    uriTemplates[v.templateAction] = v.uriTemplate
                    IntentCatalog.domainFor(v.templateAction)?.let { domains.add(it) }
                }
            }

            return Pair(uriTemplates, domains.toList())
        }
    }
}
