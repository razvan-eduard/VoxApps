package com.voxapps.commander.domain.integration

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.logging.Logger
import com.voxapps.preferences.VoxDataStore
import com.voxapps.ipc.BalGraceFlash
import com.voxapps.ipc.VoxAppInfo
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxSatelliteSchema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** The schema cache is a second store, separate from the app's settings — same machinery, so it
 *  goes through [VoxDataStore] rather than re-declaring a delegate. */
private const val SCHEMA_CACHE_STORE = "vox_satellite_schema_cache"
private val Context.satelliteSchemaDataStore get() = VoxDataStore.get(this, SCHEMA_CACHE_STORE)

/**
 * Runtime registry of Vox satellite apps, rebuilt by scanning the device at warmup / refresh. This
 * is the single dynamic source of truth for "which app owns which NLU domain" — nothing is hardcoded
 * per-app and nothing lives in intents.json. A user's own app that advertises the contract shows up
 * here automatically and becomes routable.
 *
 * Also caches each satellite's [VoxSatelliteSchema] (see the collapsed voice-command plan) —
 * fetched proactively via [refreshSchema] (Integrations' Refresh button, or the automatic push in
 * [applyPushedSchema]), never per voice command. DataStore-backed so the cache survives process
 * death; manual-only invalidation — nothing here re-fetches on a timer or a version check.
 */
object VoxSatelliteRegistry {

    private val _apps = MutableStateFlow<List<VoxAppInfo>>(emptyList())
    val apps: StateFlow<List<VoxAppInfo>> = _apps.asStateFlow()

    private val _schemaCache = MutableStateFlow<Map<String, VoxSatelliteSchema>>(emptyMap())
    val schemaCache: StateFlow<Map<String, VoxSatelliteSchema>> = _schemaCache.asStateFlow()

    private val schemaCacheKey = stringPreferencesKey("schema_cache_json")

    /** Rescan installed apps for the Vox contract + their advertised capabilities. */
    fun refresh(context: Context) {
        val found = VoxAppsDiscovery.discover(context.applicationContext)
        _apps.value = found
        Logger.log("Vox satellites: ${found.joinToString { "${it.label}[${it.domain}]" }}", TAG)
    }

    /** Domains contributed by satellites (dynamically merged into the NLU taxonomy). */
    fun domains(): List<String> = _apps.value.mapNotNull { it.domain }.distinct()

    /** Actions a satellite advertises for [domain] (from the first app that owns it). */
    fun actionsFor(domain: String): List<String> =
        _apps.value.firstOrNull { it.domain == domain }?.actions.orEmpty()

    /** ALL satellites that advertise [domain] — the candidate set for routing/disambiguation. */
    fun candidatesForDomain(domain: String): List<VoxAppInfo> =
        _apps.value.filter { it.domain == domain }

    fun handles(domain: String): Boolean = domains().contains(domain)

    /**
     * Synchronous in-memory read — the only thing the voice-command hot path touches. Null means
     * "never refreshed yet"; callers fall back to today's live per-command behavior in that case
     * (see the plan's first-run fallback).
     */
    fun cachedSchema(packageName: String): VoxSatelliteSchema? = _schemaCache.value[packageName]

    /** Hydrates the in-memory cache from disk — call once at startup before the cache is trusted. */
    suspend fun loadSchemaCacheFromDisk(context: Context) {
        val json = context.applicationContext.satelliteSchemaDataStore.data.first()[schemaCacheKey] ?: return
        _schemaCache.value = parseCacheJson(json)
    }

    /**
     * The Refresh button's action: fetch [packageName]'s schema, flashing it (Hub-style) if it's not
     * currently reachable, then persist + update the in-memory cache. Returns null if the satellite
     * never responds even after the flash retry.
     */
    suspend fun refreshSchema(context: Context, packageName: String): VoxSatelliteSchema? {
        val app = context.applicationContext
        var schema = VoxDataTransferClient.requestSchema(app, packageName)
        if (schema == null) {
            BalGraceFlash.flashThenRefocus(app, listOf(packageName))
            schema = VoxDataTransferClient.requestSchema(app, packageName)
        }
        if (schema != null) persistSchema(app, packageName, schema)
        return schema
    }

    /**
     * Called by the satellite-initiated push receiver ([VoxIpc.ACTION_SCHEMA_CHANGED]) — auto-applies
     * the fresh schema immediately, the one deliberate exception to manual-only invalidation (see the
     * plan): the satellite itself is the only thing that can know *for certain*, at the exact moment
     * its own data changed, that the cache is now wrong.
     */
    suspend fun applyPushedSchema(context: Context, packageName: String, schema: VoxSatelliteSchema) {
        persistSchema(context.applicationContext, packageName, schema)
        Logger.log("Applied pushed schema update from $packageName", TAG)
    }

    private suspend fun persistSchema(context: Context, packageName: String, schema: VoxSatelliteSchema) {
        _schemaCache.value = _schemaCache.value + (packageName to schema)
        context.satelliteSchemaDataStore.edit { prefs ->
            prefs[schemaCacheKey] = JSONObject(_schemaCache.value.mapValues { it.value.toJson() }).toString()
        }
    }

    private fun parseCacheJson(json: String): Map<String, VoxSatelliteSchema> = try {
        val o = JSONObject(json)
        o.keys().asSequence().mapNotNull { pkg ->
            VoxSatelliteSchema.fromJson(o.optString(pkg))?.let { pkg to it }
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private const val TAG = "VoxSatelliteRegistry"
}
