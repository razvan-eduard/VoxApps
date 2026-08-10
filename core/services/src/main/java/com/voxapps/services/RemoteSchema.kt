package com.voxapps.services

import android.content.Context
import com.google.gson.Gson
import com.voxapps.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * A schema that ships with the app and can be replaced, deliberately, by the repository it is
 * served from.
 *
 * Every registry needed the same thing and each wrote it again: read the bundled copy, fetch the
 * repository's, and decide between them. Three near-identical implementations differing only in the
 * file name and the log wording — and in the small print, which is where the behaviour lives.
 *
 * ### Which copy wins
 *
 *  - **`filesDir`** — what the repository served. While it exists it *is* the schema, and a check
 *    compares against it rather than against the build: the repository is the source of truth while
 *    it is in use.
 *  - **assets** — what shipped in this APK. The floor: used before the first successful fetch, when
 *    the repository cannot be reached, and permanently once the user turns the repository off.
 *
 * There is no version arithmetic anywhere, and no `schema_version` is read: a number cannot tell
 * whether a copy is right for this build — only which was written later — and comparing it silently
 * overrode what the user chose. What the app owes them instead is that the choice is theirs,
 * visible, and undoable: [resetToBundled] deletes the accepted copy and the bundled one applies
 * again. The field survives in the files as documentation for whoever edits them.
 *
 * ### What a refresh actually is
 *
 * [refresh] compares the repository's bytes with the accepted copy's **by hash**, so an unchanged
 * file costs nothing beyond the download: no write, no rebuild of whatever a registry derives, no
 * log noise. Only a real change is adopted, and only if it survives [usable] — an empty or truncated
 * catalogue is a broken download, not a deliberate emptying.
 */
private const val DEFAULT_BRANCH = "main"

class RemoteSchema<T : Any>(
    /** The file's name in the shipped folder — also how a screen names it to the user. */
    val fileName: String,
    private val type: Class<T>,
    private val usable: (T) -> Boolean,
    private val tag: String,
    /** Which folder in the repository serves this file. Defaults to the app's own — pass
     *  [SchemaRepo.SHARED] for a schema more than one app reads. */
    private val folder: String? = null,
    /** Called whenever a schema is adopted, so a registry can rebuild what it derives from it. */
    private val onLoaded: (T) -> Unit = {}
) {
    /** Where the schema in [value] came from — for a screen that shows the user what they are on. */
    enum class Source {
        /** What the app shipped with. */
        BUNDLED,

        /** Fetched, and covered by a manifest signed with the key this build embeds. */
        ACCEPTED,

        /** Fetched from a repository this build's key cannot vouch for — a fork the user chose.
         *  Kept distinct from [ACCEPTED] so a screen can say which one the user is running. */
        UNVERIFIED
    }

    /** What a [refresh] did, so a caller can say so rather than guess. */
    sealed interface Refreshed {
        /** The repository's copy differed and is now in force. */
        data object Updated : Refreshed
        /** The repository has the same bytes we already had. */
        data object Unchanged : Refreshed
        /** The repository could not be read — offline, wrong URL, moved file. */
        data class Unreachable(val reason: String) : Refreshed
        /** It was read and refused: not parseable, or empty enough to be a broken download. */
        data object Rejected : Refreshed
        /** It parsed, but no valid signature covers it and it came from the default repository. */
        data object Unsigned : Refreshed
    }

    init {
        SchemaCatalog.register(this)
    }

    private val gson = Gson()

    /** Assigned on Main in [init], read on Dispatchers.IO throughout [refresh]. */
    @Volatile private var appContext: Context? = null

    /** Written from Main ([init]) and IO ([refresh]); read from every thread that uses it. */
    @Volatile var value: T? = null
        private set

    @Volatile var source: Source = Source.BUNDLED
        private set

    val isLoaded: Boolean get() = value != null

    /** Whether an app has claimed this schema by initialising it — see [SchemaCatalog]. */
    val isInitialised: Boolean get() = appContext != null

    /** When the accepted copy was written, or null while the bundled one is in force. */
    val acceptedAt: Long?
        get() = savedFile()?.takeIf { it.exists() }?.lastModified()

    /** Loads the accepted copy if there is one, otherwise the bundled one. No network, no waiting. */
    fun init(context: Context) {
        appContext = context.applicationContext
        loadLocal()
    }

    /**
     * Fetches the repository's copy and adopts it if it differs and is usable.
     *
     * The caller decides when this happens — a toggle the user left on, or a button they pressed.
     * Nothing here decides on its own that the repository knows better.
     */
    suspend fun refresh(baseUrl: String): Refreshed = withContext(Dispatchers.IO) {
        val url = remoteUrl(baseUrl)
        Logger.log("Checking $fileName at $url", tag)

        val text = try {
            URL(url).readText()
        } catch (e: Exception) {
            Logger.log("Could not read $fileName: ${e.message}", tag)
            return@withContext Refreshed.Unreachable(e.message ?: "unreachable")
        }

        if (hash(text) == savedHash()) {
            Logger.log("$fileName is unchanged", tag)
            return@withContext Refreshed.Unchanged
        }

        val schema = parse(text)
        if (schema == null || !usable(schema)) {
            Logger.log("Refusing $fileName: it did not parse, or arrived empty", tag)
            return@withContext Refreshed.Rejected
        }

        // Parsing proves the bytes are well-formed, not that they are the maintainer's. These files
        // decide engine endpoints and the NLU prompt, and they are adopted unattended at launch, so
        // a change from the default repository has to be covered by the signed manifest before it
        // takes effect. A fork cannot sign with the embedded key, so it is accepted and marked
        // unverified rather than refused — following your own repository is a feature.
        val path = "${folder ?: SchemaRepo.appFolder}/$fileName"
        val verdict = SchemaSignature.verdictFor(path, text, SchemaSignature.isDefaultRepo(baseUrl))
        if (verdict == SchemaSignature.Verdict.FAILED) {
            Logger.log("Refusing $fileName: no valid signature covers it", tag)
            return@withContext Refreshed.Unsigned
        }

        write(text)
        adopt(schema, if (verdict == SchemaSignature.Verdict.SIGNED) Source.ACCEPTED else Source.UNVERIFIED)
        Refreshed.Updated
    }

    /**
     * Throws away the accepted copy and returns to what shipped in the app.
     *
     * The way back from a repository that served something broken — or that the user simply wants to
     * stop following. Deliberately not automatic: nothing else deletes this file.
     */
    fun resetToBundled(): Boolean {
        val deleted = savedFile()?.takeIf { it.exists() }?.delete() ?: false
        loadLocal()
        if (deleted) Logger.log("Reset $fileName to the bundled copy", tag)
        return deleted
    }

    /**
     * The repository holds these files at its root; a GitHub page URL is rewritten to the raw host
     * because the page is HTML and would parse as nothing. The timestamp defeats CDN caching, which
     * otherwise serves the previous schema for as long as it feels like.
     *
     * A branch may be named with `@`: `https://github.com/you/your-fork@develop`. Without it `main`
     * is assumed — which silently 404s to the bundled copy for a fork whose default branch is
     * anything else, and a fork is the whole point of pointing this elsewhere.
     */
    private fun remoteUrl(baseUrl: String): String {
        // Resolved per call rather than captured at construction: a registry is an object, so it may
        // be built before the Application has said which app this is.
        val path = "${SchemaRepo.FOLDER}/${folder ?: SchemaRepo.appFolder}/$fileName"
        val branch = baseUrl.substringAfterLast('@', "").takeIf { it.isNotBlank() } ?: DEFAULT_BRANCH
        val repo = baseUrl.substringBeforeLast('@')
        val base = if (repo.contains("github.com") && !repo.contains("raw.githubusercontent.com")) {
            repo.replace("github.com", "raw.githubusercontent.com").removeSuffix("/") + "/$branch/$path"
        } else {
            val root = if (repo.endsWith("/")) repo else "$repo/"
            "$root$path"
        }
        return "$base?t=${System.currentTimeMillis()}"
    }

    private fun loadLocal() {
        val saved = savedFile()?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        val fromSaved = parse(saved)
        if (fromSaved != null && usable(fromSaved)) {
            adopt(fromSaved, Source.ACCEPTED)
            return
        }
        if (saved != null) Logger.log("The accepted $fileName is unusable — falling back to the bundled copy", tag)

        val bundled = parse(assetText())
        if (bundled != null && usable(bundled)) adopt(bundled, Source.BUNDLED)
        else Logger.log("Bundled $fileName is missing or unusable", tag)
    }

    private fun adopt(schema: T, from: Source) {
        value = schema
        source = from
        Logger.log("Loaded $fileName (${from.name.lowercase()})", tag)
        onLoaded(schema)
    }

    /**
     * The bytes of the copy we hold from the repository, or null when we hold none.
     *
     * Deliberately *not* falling back to the bundled copy. The repository is the source of truth
     * while it is in use, so "have we got this already?" is a question about what was downloaded —
     * comparing against assets instead meant a first run whose repository happened to match the
     * build downloaded nothing and went on calling itself bundled, which is a different claim.
     */
    private fun savedHash(): String? =
        savedFile()?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }?.let(::hash)

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun savedFile(): File? = appContext?.let { File(it.filesDir, fileName) }

    private fun parse(text: String?): T? =
        text?.let { runCatching { gson.fromJson(it, type) }.getOrNull() }

    private fun assetText(): String? {
        val ctx = appContext ?: return null
        return runCatching {
            ctx.assets.open("${SchemaRepo.ASSET_FOLDER}/$fileName").use { it.readBytes().decodeToString() }
        }.onFailure {
            Logger.log("Failed to read $fileName from assets: ${it.message}", tag)
        }.getOrNull()
    }

    private fun write(text: String) {
        val file = savedFile() ?: return
        runCatching { file.writeText(text) }
            .onFailure { Logger.log("Failed to save $fileName: ${it.message}", tag) }
    }
}
