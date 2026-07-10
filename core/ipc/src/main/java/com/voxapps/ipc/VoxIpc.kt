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
 *  - satellite → Commander (generic LLM hook): [ACTION_LLM_PROCESS] broadcast carrying a [VoxLlmRequest]
 *    JSON in [EXTRA_LLM_PAYLOAD], guarded by [LLM_PROCESS_PERMISSION]. Fully asynchronous — Commander
 *    replies later, it does not respond within this broadcast.
 *  - Commander → satellite (LLM result): [ACTION_LLM_RESULT] explicit-intent broadcast (targeted at the
 *    request's `sourcePackage`) carrying a [VoxLlmResult] JSON in [EXTRA_LLM_PAYLOAD], guarded by the
 *    satellite's own [llmResultPermission].
 *  - satellite → Vision (OCR scan): explicit-intent `startActivity` targeting [VISION_ACTIVITY_CLASS]
 *    (in [VISION_PACKAGE]), carrying a [VoxOcrRequest] JSON in [EXTRA_OCR_PAYLOAD]. Camera capture
 *    needs a live foreground UI; the caller does this from its own foreground UI (e.g. a button tap),
 *    so no broadcast/receiver indirection is needed — direct `startActivity` hits no
 *    background-activity-launch restriction there, since that check is evaluated against the
 *    *calling* app's state, not Vision's.
 *  - Vision → satellite (OCR result): [ACTION_OCR_RESULT] explicit-intent broadcast (targeted at the
 *    request's `sourcePackage`) carrying a [VoxOcrResult] JSON in [EXTRA_OCR_PAYLOAD], guarded by the
 *    satellite's own [ocrResultPermission]. Vision only ever returns raw OCR text — classifying it
 *    (note vs. receipt, etc.) is the caller's job via its own follow-up generic-LLM-hook request.
 */
object VoxIpc {
    // --- Actions ---
    const val ACTION_COMMAND = "com.voxapps.action.VOX_COMMAND"
    const val ACTION_SPEAK = "com.voxapps.action.SPEAK"
    const val CATEGORY_VOX = "com.voxapps.category.VOX"
    const val ACTION_LLM_PROCESS = "com.voxapps.action.LLM_PROCESS"
    const val ACTION_LLM_RESULT = "com.voxapps.action.LLM_RESULT"
    const val ACTION_OCR_RESULT = "com.voxapps.action.OCR_RESULT"

    // --- Extras ---
    const val EXTRA_PAYLOAD = "com.voxapps.extra.PAYLOAD"
    const val EXTRA_RESULT = "com.voxapps.extra.RESULT"
    const val EXTRA_QUERY = "com.voxapps.extra.QUERY"
    const val EXTRA_LLM_PAYLOAD = "com.voxapps.extra.LLM_PAYLOAD"
    const val EXTRA_OCR_PAYLOAD = "com.voxapps.extra.OCR_PAYLOAD"

    // --- Command ops ---
    const val OP_CREATE = "create"
    const val OP_READ = "read"

    /** Handshake used by Commander's "Vox Apps" discovery to verify a satellite responds. */
    const val OP_PING = "ping"

    // --- Domains ---
    const val DOMAIN_NOTES = "notes"
    const val DOMAIN_VISION = "vision"

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

    /** The permission a caller must hold to send a generic LLM request to Commander. */
    const val LLM_PROCESS_PERMISSION = "com.voxapps.commander.permission.LLM_PROCESS"

    /** The permission Commander must hold to deliver an async LLM result back to a satellite package. */
    fun llmResultPermission(satellitePackage: String): String = "$satellitePackage.permission.LLM_RESULT"

    /** The permission Vision must hold to deliver an async OCR result back to a satellite package. */
    fun ocrResultPermission(satellitePackage: String): String = "$satellitePackage.permission.OCR_RESULT"

    /**
     * Vision's package and pending-scan activity, for satellites that launch Vision's UI directly
     * (explicit-intent `startActivity`, carrying a [VoxOcrRequest] JSON in [EXTRA_OCR_PAYLOAD]).
     * Camera capture needs a live foreground window; a satellite doing this from its own foreground UI
     * (e.g. a button tap) hits no background-activity-launch restriction, since that check is evaluated
     * against the *calling* app's state, not Vision's — this is simpler and instant compared to an
     * earlier broadcast-receiver design, which had no visible window of its own and required a
     * notification-tap workaround to get around the restriction.
     */
    const val VISION_PACKAGE = "com.voxapps.vision"
    const val VISION_ACTIVITY_CLASS = "com.voxapps.vision.VisionActivity"
}
