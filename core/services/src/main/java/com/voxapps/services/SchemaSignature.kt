package com.voxapps.services

import android.content.Context
import android.content.SharedPreferences
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

    /**
     * Exposed only so a test can assert this matches `remote-schemas/signing-key.pub`. The key is
     * compiled in rather than read at runtime — a trust anchor belongs inside the signed APK — and
     * that test is what stops the two copies drifting silently. See SchemaSigningKeyTest.
     */
    internal val embeddedPublicKeyForTest: String get() = PUBLIC_KEY_B64

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
     * Highest manifest serial ever accepted, so the repository cannot be walked backwards.
     *
     * A valid signature does not make a manifest current. Someone able to serve these files but not
     * to sign them could replay an *old, genuinely signed* manifest with its old schemas — every
     * signature checking out while the app downgrades to a schema naming an endpoint since
     * abandoned. Refusing a serial no greater than the last accepted one closes that.
     */
    private const val SERIAL_PREF = "schema_manifest_serial"

    @Volatile private var lastSerial: Long = 0L

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

        if (!verify(manifestText, signatureB64)) {
            Logger.log("Manifest signature did not verify — treating every schema as unsigned", TAG)
            return
        }

        // Signed, but is it current? An old manifest is signed just as validly as a new one.
        val serial = parseSerial(manifestText)
        if (serial < lastSerial) {
            Logger.log("Refusing manifest serial $serial: older than $lastSerial already accepted", TAG)
            return
        }
        lastSerial = serial
        persistSerial(serial)

        manifestVerified = true
        hashes = parseHashes(manifestText)
        Logger.log("Manifest verified (serial $serial), covering ${hashes.size} schema(s)", TAG)
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

    /**
     * Remembers the highest serial across launches — a rollback is only detectable against history.
     *
     * A fresh install has no history, so the floor comes from the manifest shipped inside the APK.
     * Otherwise a first launch would start at zero and accept any old, validly-signed manifest,
     * which is the launch an attacker would target: rollback protection that only protects
     * already-updated installs protects the wrong ones.
     */
    fun init(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences("vox_schema_signature", Context.MODE_PRIVATE)

        val remembered = prefs?.getLong(SERIAL_PREF, 0L) ?: 0L
        val shipped = runCatching {
            app.assets.open("${SchemaRepo.ASSET_FOLDER}/$MANIFEST").bufferedReader().use { it.readText() }
        }.getOrNull()?.let(::parseSerial) ?: 0L

        lastSerial = maxOf(remembered, shipped)
        if (shipped > remembered) {
            Logger.log("Rollback floor from the shipped manifest: $shipped", TAG)
        }
    }

    @Volatile private var prefs: SharedPreferences? = null

    private fun persistSerial(serial: Long) {
        prefs?.edit()?.putLong(SERIAL_PREF, serial)?.apply()
    }

    /** Exposed for the test that pins the rollback check's parsing. */
    internal fun parseSerialForTest(manifest: String): Long = parseSerial(manifest)

    private fun parseSerial(manifest: String): Long =
        Regex("\"serial\"\\s*:\\s*(\\d+)").find(manifest)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun read(url: String): String? = runCatching { URL(url).readText() }
        .onFailure { Logger.log("Fetch failed for $url: ${it.message}", TAG) }
        .getOrNull()

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
