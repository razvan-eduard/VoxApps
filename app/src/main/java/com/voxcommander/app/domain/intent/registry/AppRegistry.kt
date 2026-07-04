package com.voxcommander.app.domain.intent.registry

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy
import com.voxcommander.app.utils.Logger
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

    data class AppEntry(
        val packageName: String,
        val displayName: String,
        val domains: List<String> = emptyList(),
        val uriTemplates: Map<String, String> = emptyMap(),
        val isSystemApp: Boolean = false
    )

    private var installedPackages: Set<String> = emptySet()
    private var scannedApps: List<AppEntry> = emptyList()
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
            val type = TypeToken.getParameterized(List::class.java, AppEntry::class.java).type
            val cached: List<AppEntry> = gson.fromJson(json, type)
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

        private val PROBE_MAP: Map<String, List<UriVariant>> = mapOf(
            // ==========================================
            // 🎵 MEDIA & ENTERTAINMENT
            // ==========================================
            android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH to listOf(
                UriVariant(null, null, "Play from search (Media/Music)", TemplateActions.SEARCH)
            ),
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE to listOf(
                UriVariant(null, null, "Open Camera (Photo Mode)", requiresQuery = false)
            ),
            android.provider.MediaStore.ACTION_VIDEO_CAPTURE to listOf(
                UriVariant(null, null, "Open Camera (Video Mode)", requiresQuery = false)
            ),
            android.content.Intent.ACTION_SEARCH to listOf(
                UriVariant(null, null, "In-App Search (e.g., Netflix / Amazon)", TemplateActions.SEARCH)
            ),

            // ==========================================
            // 🗺️ NAVIGATION & LOCATION (all ACTION_VIEW with different schemes)
            // ==========================================
            android.content.Intent.ACTION_VIEW to listOf(
                UriVariant("geo:0,0?q=test", "geo:0,0?q={destination}", "View / Search Location (geo:)", TemplateActions.NAVIGATE),
                UriVariant("google.navigation:q=test", "google.navigation:q={destination}", "Turn-by-Turn Google Maps", TemplateActions.NAVIGATE),
                UriVariant("waze://?q=test&navigate=yes", "waze://?q={destination}&navigate=yes", "Turn-by-Turn Waze", TemplateActions.NAVIGATE),
                UriVariant("google.streetview:cbll=44.4,26.1", "google.streetview:cbll={lat},{lng}", "Google Street View", null),
                UriVariant("https://api.whatsapp.com/send?phone=40700000000", "https://api.whatsapp.com/send?phone={contact}", "WhatsApp Direct Message", TemplateActions.SEND),
                UriVariant("https://example.com", "{query}", "Open URL in Browser (http/https)", TemplateActions.SEARCH),
                UriVariant("market://details?id=com.spotify.music", "market://details?id={query}", "Open App in Play Store (market://)", null),
                UriVariant("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/watch?v={query}", "Play YouTube Video (LibreTube)", TemplateActions.SEARCH),
            ),

            // ==========================================
            // 💬 COMMUNICATION & SOCIAL
            // ==========================================
            android.content.Intent.ACTION_DIAL to listOf(
                UriVariant("tel:0700000000", "tel:{contact}", "Open Dialer (tel:)", null)
            ),
            android.content.Intent.ACTION_SENDTO to listOf(
                UriVariant("smsto:0700000000", "smsto:{contact}", "Compose SMS (smsto:)", null),
                UriVariant("mailto:test@example.com", "mailto:{contact}", "Compose Email (mailto:)", null)
            ),
            android.content.Intent.ACTION_SEND to listOf(
                UriVariant(null, null, "Share Text / Link (General Share Sheet)", TemplateActions.SEND, mimeType = "text/plain")
            ),

            // ==========================================
            // 🌐 WEB & SEARCH
            // ==========================================
            android.content.Intent.ACTION_WEB_SEARCH to listOf(
                UriVariant(null, null, "Web Search (Google Search)", TemplateActions.SEARCH)
            ),
            android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH to listOf(
                UriVariant(null, null, "Open System Global Search", null, requiresQuery = false)
            ),

            // ==========================================
            // ⚙️ SYSTEM SETTINGS
            // ==========================================
            android.provider.Settings.ACTION_SETTINGS to listOf(
                UriVariant(null, null, "Open General Settings", null, requiresQuery = false)
            ),
            android.provider.Settings.ACTION_WIFI_SETTINGS to listOf(
                UriVariant(null, null, "Open Wi-Fi Settings", null, requiresQuery = false)
            ),
            android.provider.Settings.ACTION_BLUETOOTH_SETTINGS to listOf(
                UriVariant(null, null, "Open Bluetooth Settings", null, requiresQuery = false)
            ),
            android.provider.Settings.ACTION_SOUND_SETTINGS to listOf(
                UriVariant(null, null, "Open Sound & Volume Settings", null, requiresQuery = false)
            ),
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS to listOf(
                UriVariant("package:com.voxcommander.app", "package:{query}", "Open App Info Details (package:)", null)
            ),
            android.provider.Settings.ACTION_DISPLAY_SETTINGS to listOf(
                UriVariant(null, null, "Open Display Settings", null, requiresQuery = false)
            ),

            // ==========================================
            // ⏰ CLOCK, ALARMS & PRODUCTIVITY
            // ==========================================
            android.provider.AlarmClock.ACTION_SET_TIMER to listOf(
                UriVariant(null, null, "Set a Timer", null)
            ),
            android.provider.AlarmClock.ACTION_SET_ALARM to listOf(
                UriVariant(null, null, "Set an Alarm", null)
            ),
            android.provider.AlarmClock.ACTION_SHOW_ALARMS to listOf(
                UriVariant(null, null, "Show Alarms List", null, requiresQuery = false)
            ),
            android.content.Intent.ACTION_INSERT to listOf(
                UriVariant(null, null, "Insert Calendar Event", null)
            ),
            "com.google.android.gms.actions.CREATE_NOTE" to listOf(
                UriVariant(null, null, "Create Note (Google Keep)", null)
            )
        )

        val ALL_ACTIONS: List<String> = PROBE_MAP.keys.toList()

        /**
         * Probes which intent variants a package supports by querying PackageManager.
         * For each action, probes each URI variant separately.
         * Returns only the variants that passed the probe, plus "Launch app" fallback.
         */
        fun probeSupported(context: Context, packageName: String): List<IntentOption> {
            val pm = context.packageManager
            val result = mutableListOf<IntentOption>()

            for ((action, variants) in PROBE_MAP) {
                for (variant in variants) {
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
                    when (v.templateAction) {
                        TemplateActions.NAVIGATE -> domains.add(IntentTaxonomy.Domains.MAPS)
                        TemplateActions.SEARCH -> domains.add(IntentTaxonomy.Domains.AUDIO)
                        TemplateActions.SEND -> domains.add(IntentTaxonomy.Domains.MESSAGING)
                    }
                }
            }

            return Pair(uriTemplates, domains.toList())
        }
    }
}
