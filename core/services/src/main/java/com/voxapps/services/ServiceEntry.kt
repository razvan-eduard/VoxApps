package com.voxapps.services

/**
 * One declared service, in the same words whatever declared it.
 *
 * The same idea arrives in three shapes across the apps: an engine key to look up in the model
 * registry, a provider name to look up in the search registry, and an object parsed straight out of
 * a schema. Every screen that offered a choice between them re-derived the same four facts its own
 * way — what to call it, whether it needs a credential, where to get one, what to probe — and no
 * shared component could ask "do you need a key?" without first knowing which registry the item came
 * from. This is that question, asked once.
 *
 * **Declarations only.** What a service *is* belongs here; what is currently true about it does not.
 * The stored credential, whether a model is on disk, whether this particular device can run it —
 * those stay live lookups at the call site. A snapshot of them inside an entry is how a screen once
 * came to test the credential the registry had last been handed rather than the one on screen.
 */
interface ServiceEntry {

    /** Stable id: the engine key, the provider name, the schema's `id`. Used for probes and logs. */
    val id: String

    /** A translation key for the label, when the declaration names one. */
    val labelKey: String?

    /** What to show when [labelKey] is absent or untranslated — usually the declared label. */
    val fallbackLabel: String

    val runtime: ServiceRuntime

    /** Whether a credential must be supplied before this service can answer. */
    val requiresCredential: Boolean

    /**
     * Whose credential it is. Nearly always [id] — except a search provider that borrows an engine's
     * key, where entering it here and on the engine's own screen has to mean the same slot.
     */
    val credentialOwnerId: String get() = id

    /** Where the user obtains a credential, when the declaration says. */
    val apiKeyUrl: String?

    /** A translation key for the sentence around that link, when the declaration names one. */
    val helpTextKey: String? get() = null

    /**
     * Whether this service has model files of its own to choose between and download.
     *
     * A declaration, not a count: it says the service *has* such a list, not that anything in it is
     * currently on disk. What that list looks like is the app's business — core has no idea what a
     * model download looks like.
     */
    val hasDownloadableModels: Boolean get() = false

    /**
     * What to probe to find out whether it answers, or null when there is nothing to reach.
     *
     * Null is the honest answer for an on-device engine, and for a service whose credential is
     * validated inside a vendor SDK with no URL to call. A permanently failing test would be worse
     * than none.
     */
    fun probeSpec(credential: String?): ProbeSpec?
}

/** How a service runs, which decides what can be said about it and what can be asked of it. */
enum class ServiceRuntime {
    /** Answered over the network; needs a credential, and can be probed. */
    CLOUD,

    /** A downloadable artefact on disk — the engine picks up a file the user chose. */
    LOCAL_FILE,

    /** On-device but supplied by the OS, so it can simply be absent on a given device. */
    DEVICE,

    /** On-device and built into the engine: nothing to download, nothing to resolve. */
    BUILTIN;

    companion object {
        /** The vocabulary the engine schema already uses, mapped onto this one. */
        fun fromKey(key: String?): ServiceRuntime? = when (key) {
            "cloud" -> CLOUD
            "local_file" -> LOCAL_FILE
            "android_local" -> DEVICE
            "device_builtin" -> BUILTIN
            else -> null
        }
    }
}

/**
 * A [ServiceEntry] assembled by a mapper, for the registries whose own types are a key or a name
 * rather than an object. A schema-parsed class implements the interface directly instead.
 */
data class DeclaredService(
    override val id: String,
    override val fallbackLabel: String,
    override val runtime: ServiceRuntime,
    override val requiresCredential: Boolean,
    override val labelKey: String? = null,
    override val credentialOwnerId: String = id,
    override val apiKeyUrl: String? = null,
    override val helpTextKey: String? = null,
    override val hasDownloadableModels: Boolean = false,
    private val probe: (String?) -> ProbeSpec? = { null }
) : ServiceEntry {
    override fun probeSpec(credential: String?): ProbeSpec? = probe(credential)
}
