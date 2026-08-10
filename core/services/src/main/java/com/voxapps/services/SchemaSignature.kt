package com.voxapps.services

import com.voxapps.logging.Logger
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64

/**
 * Whether a schema is the one the maintainer published, rather than merely the one the server sent.
 *
 * Every launch fetches the schemas and adopts what it gets. They decide engine endpoints and the NLU
 * prompt, so whoever can serve that path can change where every install sends speech — at the next
 * launch, with no app update and nothing for the user to accept. The SHA-256 already in
 * [RemoteSchema] compares a download against the *previous download*, which answers "did this
 * change?", not "is this genuine?".
 *
 * The repository publishes `remote-schemas/manifest.json` — every schema path and its SHA-256 — and
 * `manifest.json.sig`, an ECDSA signature over that manifest. This holds the public half, so a
 * changed schema is only adopted when its hash appears in a manifest signed by the key whose private
 * half never leaves the maintainer.
 *
 * Signing one manifest rather than each file means adding or removing a file is as detectable as
 * editing one.
 *
 * **Forks are still allowed.** Following your own repository is a feature, and a fork cannot sign
 * with this key. So the rule depends on which repository is in use: the default one must verify, and
 * anything else is accepted unverified — the user typed that URL — and reported as such, rather than
 * silently trusted. See [verdictFor].
 */
object SchemaSignature {

    /** SPKI, base64. The private half signs in CI (`SCHEMA_SIGNING_KEY`) and lives nowhere else. */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvyVjjmZu2RbRohHdaSJf2S7eIqSOMLd7mJXa/mmPK0tAXVdBESUAe+mGHmVP9aT18yCizw2ID7hdRc3TKBl/Qg=="

    private const val MANIFEST = "manifest.json"
    private const val TAG = "SchemaSignature"

    /** What is known about a downloaded schema's provenance. */
    enum class Verdict {
        /** Its hash appears in a manifest signed by the embedded key. */
        SIGNED,

        /** A non-default repository with no usable signature. Allowed, and shown as unverified. */
        UNVERIFIED_FORK,

        /** The default repository served something the signature does not cover. Refuse it. */
        FAILED
    }

    /** Hashes from the last fetched manifest, keyed by `<folder>/<file>`. Empty until fetched. */
    @Volatile private var hashes: Map<String, String> = emptyMap()

    @Volatile private var manifestVerified = false

    /**
     * Fetches and verifies the manifest for [baseUrl]. Call once per refresh cycle, before the
     * schemas themselves — [SchemaCatalog.refreshAll] does.
     */
    fun fetchManifest(baseUrl: String) {
        hashes = emptyMap()
        manifestVerified = false

        val manifestText = read(urlFor(baseUrl, MANIFEST)) ?: run {
            Logger.log("No manifest at this repository", TAG)
            return
        }
        val signatureB64 = read(urlFor(baseUrl, "$MANIFEST.sig"))?.trim() ?: run {
            Logger.log("Manifest is present but unsigned", TAG)
            return
        }

        manifestVerified = verify(manifestText, signatureB64)
        if (!manifestVerified) {
            Logger.log("Manifest signature did not verify — treating every schema as unsigned", TAG)
            return
        }
        hashes = parseHashes(manifestText)
        Logger.log("Manifest verified, covering ${hashes.size} schema(s)", TAG)
    }

    /**
     * Whether [text] fetched for [path] may be adopted, given which repository it came from.
     *
     * [isDefaultRepo] is the whole reason a fork keeps working: the embedded key can only ever
     * vouch for the maintainer's repository, so demanding a valid signature everywhere would make
     * "follow your own repository" impossible rather than merely unverified.
     */
    fun verdictFor(path: String, text: String, isDefaultRepo: Boolean): Verdict {
        val expected = hashes[path]
        if (manifestVerified && expected != null && expected == sha256(text)) return Verdict.SIGNED

        return if (isDefaultRepo) {
            Logger.log("Refusing $path: not covered by a valid signature from the default repository", TAG)
            Verdict.FAILED
        } else {
            Verdict.UNVERIFIED_FORK
        }
    }

    /**
     * True when [baseUrl] is the repository this build's embedded key can vouch for.
     *
     * Every name the repository has had counts: the URL is persisted per install, so one that saved
     * the pre-rename name is following the same place and must not be demoted to "someone's fork".
     */
    fun isDefaultRepo(baseUrl: String): Boolean {
        val configured = baseUrl.substringBeforeLast('@').trimEnd('/')
        return SchemaRepo.KNOWN_BASE_URLS.any { it.trimEnd('/') == configured }
    }

    private fun urlFor(baseUrl: String, file: String): String {
        val branch = baseUrl.substringAfterLast('@', "").takeIf { it.isNotBlank() } ?: "main"
        val repo = baseUrl.substringBeforeLast('@')
        val path = "${SchemaRepo.FOLDER}/$file"
        val base = if (repo.contains("github.com") && !repo.contains("raw.githubusercontent.com")) {
            repo.replace("github.com", "raw.githubusercontent.com").removeSuffix("/") + "/$branch/$path"
        } else {
            (if (repo.endsWith("/")) repo else "$repo/") + path
        }
        return "$base?t=${System.currentTimeMillis()}"
    }

    private fun read(url: String): String? = runCatching { URL(url).readText() }.getOrNull()

    private fun verify(manifest: String, signatureB64: String): Boolean = runCatching {
        val keyBytes = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(manifest.toByteArray())
            verify(Base64.decode(signatureB64, Base64.DEFAULT))
        }
    }.getOrElse {
        Logger.log("Could not check the manifest signature: ${it.message}", TAG)
        false
    }

    /** Deliberately not Gson: this parses input whose whole point is that it is not yet trusted. */
    private fun parseHashes(manifest: String): Map<String, String> =
        Regex("\"([^\"]+\\.json)\"\\s*:\\s*\"([0-9a-f]{64})\"")
            .findAll(manifest)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
