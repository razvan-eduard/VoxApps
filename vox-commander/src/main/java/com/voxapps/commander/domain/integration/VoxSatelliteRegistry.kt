package com.voxapps.commander.domain.integration

import android.content.Context
import com.voxapps.commander.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime registry of Vox satellite apps, rebuilt by scanning the device at warmup / refresh. This
 * is the single dynamic source of truth for "which app owns which NLU domain" — nothing is hardcoded
 * per-app and nothing lives in intents.json. A user's own app that advertises the contract shows up
 * here automatically and becomes routable.
 */
object VoxSatelliteRegistry {

    private val _apps = MutableStateFlow<List<VoxAppInfo>>(emptyList())
    val apps: StateFlow<List<VoxAppInfo>> = _apps.asStateFlow()

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

    private const val TAG = "VoxSatelliteRegistry"
}
