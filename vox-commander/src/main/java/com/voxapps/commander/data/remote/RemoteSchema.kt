package com.voxapps.commander.data.remote

import android.content.Context
import com.google.gson.Gson
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * A schema that ships with the app and can be replaced by the repository the user configured.
 *
 * Every registry needed the same four things and each wrote them again: read the bundled copy, keep
 * a working copy in `filesDir`, fetch the repository's copy, and refuse a copy older than the one
 * bundled. Three near-identical implementations, differing only in the file name and the log
 * wording — and in the small print, which is where the behaviour actually lives: which failure
 * falls back to assets, what counts as "usable", when the local file is overwritten.
 *
 * The rules, once:
 *
 *  - the bundled copy is the floor. A remote copy with a lower `schema_version` is ignored, because
 *    the app was built against the newer one and a downgrade silently removes fields the code reads;
 *  - a parse failure or an unusable result recovers from assets rather than leaving nothing loaded;
 *  - the working copy lives in `filesDir` so a fetched schema survives a restart, and is refreshed
 *    from assets whenever the app ships something newer.
 *
 * [usable] is what the owner considers a real schema — a catalog with no entries usually means an
 * outdated file rather than a deliberate emptiness, and adopting it would leave the feature dead.
 */
class RemoteSchema<T : Any>(
    private val fileName: String,
    private val type: Class<T>,
    private val versionOf: (T) -> Int,
    private val usable: (T) -> Boolean,
    private val tag: String,
    /** Called whenever a new schema is adopted, so a registry can rebuild what it derives from it. */
    private val onLoaded: (T) -> Unit = {}
) {
    private val gson = Gson()

    /** Assigned on Main in [init], read on Dispatchers.IO throughout [fetchRemote]. */
    @Volatile private var appContext: Context? = null

    /** Written from Main ([init]) and IO ([fetchRemote]); read from every thread that uses it. */
    @Volatile var value: T? = null
        private set

    val isLoaded: Boolean get() = value != null

    fun init(context: Context) {
        appContext = context.applicationContext
        loadLocal()
    }

    /**
     * Fetches the repository's copy, keeping whatever is already loaded if it cannot be used.
     *
     * Returns whether a usable schema is loaded afterwards — not whether the network call
     * succeeded, since falling back to the bundled copy is a success from the caller's point of view.
     */
    suspend fun fetchRemote(repo: SettingsRepository, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!force && value != null) return@withContext true

            val url = remoteUrl(repo.getSettingsSnapshot().modelRepoBaseUrl)
            Logger.log("Fetching remote $fileName from: $url", tag)

            try {
                val jsonText = URL(url).readText()
                val schema = parse(jsonText)
                if (schema == null || !usable(schema)) {
                    Logger.log("Remote $fileName is missing or empty — keeping the local copy", tag)
                    return@withContext value != null
                }

                val assetVersion = parse(assetText())?.let(versionOf) ?: 0
                if (assetVersion > versionOf(schema)) {
                    Logger.log(
                        "Remote $fileName is v${versionOf(schema)}, bundled is v$assetVersion — keeping the bundled copy",
                        tag
                    )
                    loadLocal()
                } else {
                    writeLocal(jsonText)
                    adopt(schema, "remote")
                }
                true
            } catch (e: Exception) {
                Logger.log("Remote $fileName fetch failed: ${e.message}. Falling back to the local copy.", tag)
                loadLocal()
                value != null
            }
        }

    /**
     * The repository holds these files at its root; a GitHub page URL is rewritten to the raw host
     * because the page is HTML and would parse as nothing. The timestamp defeats CDN caching, which
     * otherwise serves the previous schema for as long as it feels like.
     */
    private fun remoteUrl(baseUrl: String): String {
        val base = if (baseUrl.contains("github.com") && !baseUrl.contains("raw.githubusercontent.com")) {
            baseUrl.replace("github.com", "raw.githubusercontent.com").removeSuffix("/") + "/main/$fileName"
        } else {
            if (baseUrl.endsWith("/")) "$baseUrl$fileName" else "$baseUrl/$fileName"
        }
        return "$base?t=${System.currentTimeMillis()}"
    }

    /** Loads the working copy, refreshing it from assets first when the app ships something newer. */
    private fun loadLocal() {
        val ctx = appContext ?: return
        refreshFromAssetsIfNewer()

        val local = File(ctx.filesDir, fileName)
        val fromFile = if (local.exists()) runCatching { parse(local.readText()) }.getOrNull() else null
        if (fromFile != null && usable(fromFile)) {
            adopt(fromFile, "filesDir")
            return
        }

        // Anything else — absent, unparseable, or empty enough to be an outdated file — is recovered
        // from the bundled copy, which is the one the running code was built against.
        Logger.log("Local $fileName is unusable — recovering from assets", tag)
        val text = assetText() ?: return
        val fromAssets = parse(text)
        if (fromAssets != null && usable(fromAssets)) {
            writeLocal(text)
            adopt(fromAssets, "assets")
        } else {
            Logger.log("Bundled $fileName is unusable too", tag)
        }
    }

    private fun refreshFromAssetsIfNewer() {
        val ctx = appContext ?: return
        val local = File(ctx.filesDir, fileName)
        val text = assetText() ?: return

        val localVersion = if (local.exists()) {
            runCatching { parse(local.readText())?.let(versionOf) }.getOrNull() ?: 0
        } else 0
        val assetVersion = parse(text)?.let(versionOf) ?: 0

        if (!local.exists() || assetVersion > localVersion) {
            writeLocal(text)
            Logger.log("Copied $fileName from assets (bundled v$assetVersion, local v$localVersion)", tag)
        }
    }

    private fun adopt(schema: T, source: String) {
        value = schema
        Logger.log("Loaded $fileName v${versionOf(schema)} from $source", tag)
        onLoaded(schema)
    }

    private fun parse(text: String?): T? =
        text?.let { runCatching { gson.fromJson(it, type) }.getOrNull() }

    private fun assetText(): String? {
        val ctx = appContext ?: return null
        return runCatching {
            ctx.assets.open(fileName).use { it.readBytes().decodeToString() }
        }.onFailure {
            Logger.log("Failed to read $fileName from assets: ${it.message}", tag)
        }.getOrNull()
    }

    private fun writeLocal(text: String) {
        val ctx = appContext ?: return
        runCatching { File(ctx.filesDir, fileName).writeText(text) }
            .onFailure { Logger.log("Failed to write $fileName: ${it.message}", tag) }
    }
}
