package com.voxapps.commander.domain.media

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.services.RemoteSchema
import com.voxapps.services.ProbeSpec

/**
 * The backends that can answer "play this video", declared rather than compiled.
 *
 * This was the last family still written in Kotlin: a list of four Piped instances, a list of
 * fifty-odd region codes, and the choice between Piped and NewPipe as a pair of literals in the
 * settings screen. The instance list is the one that matters — public Piped instances go down and
 * get replaced regularly, and a hardcoded list means the fix is an app release, which is exactly
 * the situation the remote schema mechanism exists for.
 *
 * NewPipe stays compiled, and says so: it is a library parsing YouTube on device, with no endpoint
 * to declare and nothing a schema could usefully say about it beyond that it exists.
 */
object MediaServiceRegistry {

    private const val TAG = "MediaServiceRegistry"

    data class MediaSchema(
        @SerializedName("schema_version") val schemaVersion: Int = 1,
        val backends: List<MediaBackend> = emptyList()
    )

    data class MediaBackend(
        val id: String = "",
        val label: String = "",
        /** The one chosen when nothing is stored. */
        @SerializedName("default") val isDefault: Boolean = false,
        /** `device_builtin` for a backend that is compiled in and has no endpoint — NewPipe. */
        val runtime: String? = null,
        /** Interchangeable instances of the same API. Plural because that is what they are: one
         *  service, several hosts, any of which can answer — and any of which can go away. */
        val endpoints: List<String> = emptyList(),
        @SerializedName("probe_url") val probeUrl: String? = null,
        val regions: List<MediaRegion> = emptyList()
    ) {
        val isBuiltIn: Boolean get() = runtime == RUNTIME_BUILT_IN
    }

    data class MediaRegion(val code: String = "", val label: String = "")

    private val schema = RemoteSchema(
        fileName = "media_services.json",
        type = MediaSchema::class.java,
        usable = { it.backends.isNotEmpty() },
        tag = TAG
    )

    fun init(context: Context) = schema.init(context)


    fun backends(): List<MediaBackend> = schema.value?.backends ?: FALLBACK.backends

    fun byId(id: String): MediaBackend? = backends().firstOrNull { it.id == id }

    /** The declared default, or the first declared backend when none claims to be. */
    fun defaultBackendId(): String =
        (backends().firstOrNull { it.isDefault } ?: backends().firstOrNull())?.id.orEmpty()

    fun endpoints(backendId: String): List<String> = byId(backendId)?.endpoints ?: emptyList()

    fun regions(backendId: String): List<MediaRegion> = byId(backendId)?.regions ?: emptyList()

    /** The endpoint used when nothing is stored — the first declared instance. */
    fun defaultEndpoint(backendId: String): String? = endpoints(backendId).firstOrNull()

    /** How to test [endpoint] of [backendId], or null for a backend with nothing to call. */
    fun probeSpecFor(backendId: String, endpoint: String?): ProbeSpec? {
        val backend = byId(backendId) ?: return null
        val target = endpoint?.takeIf { it.isNotBlank() } ?: defaultEndpoint(backendId)
        return ProbeSpec.from(id = backend.id, endpoint = target, probeUrl = backend.probeUrl)
    }

    const val RUNTIME_BUILT_IN = "device_builtin"

    /**
     * Enough to keep video playback working if the asset cannot be read at all.
     *
     * Only the two backend identities and one instance — the settings screen stays usable and the
     * default path still resolves. A longer copy of the instance list here would be a second list
     * to keep current, which is the problem this schema exists to remove.
     */
    private val FALLBACK = MediaSchema(
        backends = listOf(
            MediaBackend(
                id = "piped",
                label = "Piped API",
                isDefault = true,
                probeUrl = "health",
                endpoints = listOf("https://pipedapi.kavin.rocks")
            ),
            MediaBackend(id = "newpipe", label = "NewPipe Extractor", runtime = RUNTIME_BUILT_IN)
        )
    )
}
