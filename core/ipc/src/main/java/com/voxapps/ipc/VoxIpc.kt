package com.voxapps.ipc

/**
 * Single source of truth for the Vox cross-app wire contract (constants only — no runtime logic).
 * Commander and each satellite `implementation(project(":core:ipc"))`; a third-party app can still
 * integrate by mirroring these plain strings locally. Nothing here couples the apps at runtime.
 *
 * Directions:
 *  - Commander → satellite: [ACTION_COMMAND] broadcast carrying a [VoxCommand] JSON in [EXTRA_PAYLOAD].
 *  - satellite → Commander (read result): ordered-broadcast `resultData` carrying a [VoxResult] JSON.
 *  - any app → Commander TTS: [ACTION_SPEAK] broadcast carrying text in [EXTRA_QUERY].
 */
object VoxIpc {
    // --- Actions ---
    const val ACTION_COMMAND = "com.voxapps.action.VOX_COMMAND"
    const val ACTION_SPEAK = "com.voxapps.action.SPEAK"
    const val CATEGORY_VOX = "com.voxapps.category.VOX"

    // --- Extras ---
    const val EXTRA_PAYLOAD = "com.voxapps.extra.PAYLOAD"
    const val EXTRA_RESULT = "com.voxapps.extra.RESULT"
    const val EXTRA_QUERY = "com.voxapps.extra.QUERY"

    // --- Command ops ---
    const val OP_CREATE = "create"
    const val OP_READ = "read"

    /** Handshake used by Commander's "Vox Apps" discovery to verify a satellite responds. */
    const val OP_PING = "ping"

    // --- Domains ---
    const val DOMAIN_NOTES = "notes"

    /**
     * Capability advertising — a satellite declares these as `<meta-data>` on its command receiver
     * so Commander can discover, at warmup/refresh, WHICH NLU domain it handles and WHICH ops it
     * supports, without the app running. This is how a user's own app self-registers.
     *  - META_DOMAIN: the NLU domain string it owns (e.g. "notes").
     *  - META_ACTIONS: comma-separated ops it accepts (e.g. "create,read").
     *  - META_LABEL: human-friendly name (optional; falls back to the app label).
     */
    const val META_DOMAIN = "com.voxapps.vox.domain"
    const val META_ACTIONS = "com.voxapps.vox.actions"
    const val META_LABEL = "com.voxapps.vox.label"

    /**
     * Optional free-text NLU hint the satellite declares to teach Commander how to extract its
     * domain-specific fields (e.g. "put the amount in extras.amount"). Injected into the NLU prompt
     * dynamically, so a rich satellite needs no edits to Commander/models.json. Omit for simple apps.
     */
    const val META_NLU_HINT = "com.voxapps.vox.nluHint"

    // --- Custom permissions (guard the exported receivers) ---
    const val SPEAK_PERMISSION = "com.voxapps.commander.permission.SPEAK"

    /** The permission a caller must hold to send commands to a satellite package. */
    fun commandPermission(satellitePackage: String): String = "$satellitePackage.permission.COMMAND"
}
