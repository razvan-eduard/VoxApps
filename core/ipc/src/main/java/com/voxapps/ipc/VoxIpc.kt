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
 *    JSON in [EXTRA_LLM_PAYLOAD], guarded by [PERMISSION_LLM_PROCESS]. Fully asynchronous — Commander
 *    replies later, it does not respond within this broadcast.
 *  - Commander → satellite (LLM result): [ACTION_LLM_RESULT] explicit-intent broadcast (targeted at the
 *    request's `sourcePackage`) carrying a [VoxLlmResult] JSON in [EXTRA_LLM_PAYLOAD], guarded by
 *    [PERMISSION_LLM_RESULT].
 *  - satellite → Vision (OCR scan): explicit-intent `startActivity` targeting [VISION_ACTIVITY_CLASS]
 *    (in [VISION_PACKAGE]), carrying a [VoxOcrRequest] JSON in [EXTRA_OCR_PAYLOAD]. Camera capture
 *    needs a live foreground UI; the caller does this from its own foreground UI (e.g. a button tap),
 *    so no broadcast/receiver indirection is needed — direct `startActivity` hits no
 *    background-activity-launch restriction there, since that check is evaluated against the
 *    *calling* app's state, not Vision's.
 *  - Vision → satellite (OCR result): [ACTION_OCR_RESULT] explicit-intent broadcast (targeted at the
 *    request's `sourcePackage`) carrying a [VoxOcrResult] JSON in [EXTRA_OCR_PAYLOAD], guarded by
 *    [PERMISSION_OCR_RESULT]. Vision only ever returns raw OCR text — classifying it (note vs.
 *    receipt, etc.) is the caller's job via its own follow-up generic-LLM-hook request.
 *  - Commander → satellite (schema fetch): [OP_GET_SCHEMA] over the same [ACTION_COMMAND]/
 *    [PERMISSION_COMMAND] request-response channel as ping/export/import — the satellite replies with
 *    a [VoxSatelliteSchema] JSON in [VoxResult.text]. Proactively fetched from Commander's Integrations
 *    screen and cached; not called per voice command (see [VoxDataTransferClient.requestSchema]).
 *  - satellite → Commander (schema changed, push): [ACTION_SCHEMA_CHANGED] explicit-intent broadcast,
 *    fire-and-forget, carrying a fresh [VoxSatelliteSchema] JSON in [EXTRA_SCHEMA_PAYLOAD], guarded by
 *    [PERMISSION_SCHEMA_CHANGED]. The only satellite-initiated broadcast in this contract — fired the
 *    instant a satellite's own dynamic context (categories, etc.) changes, so Commander's cache never
 *    needs a TTL or a poll to stay correct.
 *  - any first-party app → Commander (capability query): [ACTION_CAPABILITY_QUERY] ordered broadcast,
 *    request-response like [VoxAppsDiscovery.ping], guarded by [PERMISSION_CAPABILITY_QUERY]. Global
 *    Commander engine state (e.g. "is the configured engine multimodal"), not per-satellite data — kept
 *    separate from [OP_GET_SCHEMA] on purpose.
 *
 * [PERMISSION_COMMAND]/[PERMISSION_LLM_RESULT]/[PERMISSION_OCR_RESULT]/[PERMISSION_LLM_PROCESS]/
 * [PERMISSION_SCHEMA_CHANGED]/[PERMISSION_CAPABILITY_QUERY] are declared (both `<permission>` and the
 * matching `<uses-permission>`) once in this module's own `AndroidManifest.xml` — manifest merger
 * folds them into every app that depends on `:core:ipc`, so every Vox app automatically both offers
 * and holds all of them, with zero per-app manifest edits ever. `protectionLevel="signature"` ties the
 * actual grant to "signed with the same developer key", so one shared name per contract type enforces
 * the identical trust boundary N per-satellite names used to.
 */
object VoxIpc {
    // --- Actions ---
    const val ACTION_COMMAND = "com.voxapps.action.VOX_COMMAND"
    const val ACTION_SPEAK = "com.voxapps.action.SPEAK"
    const val CATEGORY_VOX = "com.voxapps.category.VOX"
    const val ACTION_LLM_PROCESS = "com.voxapps.action.LLM_PROCESS"
    const val ACTION_LLM_RESULT = "com.voxapps.action.LLM_RESULT"
    const val ACTION_OCR_RESULT = "com.voxapps.action.OCR_RESULT"

    /** Satellite → Commander, fire-and-forget push. See class doc comment. */
    const val ACTION_SCHEMA_CHANGED = "com.voxapps.action.SCHEMA_CHANGED"

    /** Any first-party app → Commander, ordered-broadcast request-response. See class doc comment. */
    const val ACTION_CAPABILITY_QUERY = "com.voxapps.action.CAPABILITY_QUERY"

    // --- Extras ---
    const val EXTRA_PAYLOAD = "com.voxapps.extra.PAYLOAD"
    const val EXTRA_RESULT = "com.voxapps.extra.RESULT"
    const val EXTRA_QUERY = "com.voxapps.extra.QUERY"
    const val EXTRA_LLM_PAYLOAD = "com.voxapps.extra.LLM_PAYLOAD"
    const val EXTRA_OCR_PAYLOAD = "com.voxapps.extra.OCR_PAYLOAD"
    const val EXTRA_SCHEMA_PAYLOAD = "com.voxapps.extra.SCHEMA_PAYLOAD"

    /**
     * The sender's own package name, carried explicitly in [ACTION_SCHEMA_CHANGED]'s extras — a plain
     * broadcast doesn't reliably expose the caller's identity (same reason [VoxLlmRequest.sourcePackage]
     * exists), so the satellite must state who it is rather than Commander inferring it from the intent.
     */
    const val EXTRA_SOURCE_PACKAGE = "com.voxapps.extra.SOURCE_PACKAGE"

    /**
     * Epoch-millis day to pre-select, carried on a plain explicit-intent `startActivity` (not the
     * broadcast bus) from one app's day-tap-through to another's main Activity — e.g. Vox Calendar
     * opening Notes/Expenses scoped to one day. Single source of truth for the extra key so the
     * sender/receiver apps can't silently drift on the string, even though delivery itself is a
     * plain Intent, not an [ACTION_COMMAND] broadcast (mirrors [VISION_PACKAGE]'s direct-launch
     * pattern, not the command/result contract above).
     */
    const val EXTRA_SELECTED_DATE = "com.voxapps.extra.SELECTED_DATE"

    /**
     * ID of a specific record to open for editing (e.g. following a successful scan or a recovery
     * flow). Used by satellite apps to deep-link into their own editor screens.
     */
    const val EXTRA_EXPENSE_ID = "com.voxapps.extra.EXPENSE_ID"

    /** Notes' equivalent of [EXTRA_EXPENSE_ID] — same deep-link-to-editor purpose, one per satellite
     *  since each already had its own locally-scoped constant of this exact string value before this
     *  one existed (see `NotesActivity.EXTRA_EDIT_NOTE_ID`, kept as an alias for source compat). */
    const val EXTRA_EDIT_NOTE_ID = "com.voxapps.notes.EXTRA_EDIT_NOTE_ID"

    // --- Command ops ---
    const val OP_CREATE = "create"
    const val OP_READ = "read"

    /** Handshake used by Commander's "Vox Apps" discovery to verify a satellite responds. */
    const val OP_PING = "ping"

    /**
     * Vox Hub's data-portability ops. A satellite that advertises these in [META_ACTIONS] replies to
     * [OP_EXPORT] with its full settings+data as a JSON [VoxResult.text], and accepts the same shape
     * back via [OP_IMPORT]'s [VoxCommand.text]. See [VoxDataTransferClient].
     */
    const val OP_EXPORT = "export"
    const val OP_IMPORT = "import"

    /**
     * A satellite's contract for the collapsed voice-command extraction flow: whether it needs a
     * second LLM pass, and — if so — the cacheable [VoxSatelliteSchema] to run it with. Same
     * request-response channel as [OP_PING]/[OP_EXPORT]/[OP_IMPORT]; the reply's [VoxResult.text] is
     * a [VoxSatelliteSchema] JSON. See [VoxDataTransferClient.requestSchema].
     */
    const val OP_GET_SCHEMA = "get_schema"

    /**
     * Vox Hub's peer-to-peer device sync ops (NFC pairing + Bluetooth transport) — deliberately
     * separate from [OP_EXPORT]/[OP_IMPORT], which is a one-directional *restore* (wipe pre-existing
     * rows, insert the snapshot verbatim). [OP_SYNC_EXPORT] returns only entries with
     * `updatedAt > `[VoxCommand.since] (plus deletion tombstones with `deletedAt > since`), optionally
     * restricted to [VoxCommand.scopeNames] (category/layer *names*, not ids — ids are a local Room
     * sequence with no meaning on another phone; names are what category/layer reconciliation already
     * keys on). [OP_SYNC_MERGE] applies an incoming delta of that same shape: insert-if-new,
     * last-write-wins-by-`updatedAt` on a uid collision, delete-on-tombstone — never a blind
     * insert-then-wipe like [OP_IMPORT]. See [VoxDataTransferClient.requestSyncExport]/
     * [requestSyncMerge].
     */
    const val OP_SYNC_EXPORT = "sync_export"
    const val OP_SYNC_MERGE = "sync_merge"

    /**
     * VoxConnect Bridge's media-control relay (Hub → Commander, over this same request-response
     * channel) — Commander is the only app holding the notification-listener permission grant that
     * media-session access requires (see `MediaSessionListenerService`), so a network client never
     * touches media sessions directly; it always goes through Commander via this op.
     * [VoxCommand.mediaAction] selects the action: "status" (returns now-playing metadata as JSON in
     * [VoxResult.text]), "play", "pause", "next", "prev".
     */
    const val OP_MEDIA_CONTROL = "media_control"

    /** [VoxCommand.exportScope] values — which slice of an app's export payload to include. */
    const val EXPORT_SCOPE_SETTINGS = "settings"
    const val EXPORT_SCOPE_DATA = "data"
    const val EXPORT_SCOPE_BOTH = "both"

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

    /**
     * The task-string a satellite's `OcrResultReceiver` advertises via `<meta-data>` — what Vision's
     * dynamic dispatcher discovery reads to build a "send scan to this app" button, alongside the
     * standard `queryBroadcastReceivers` capability scan (mirrors [META_DOMAIN] et al.'s pattern, just
     * scoped to the OCR-result contract instead of the command contract).
     */
    const val META_OCR_TASK = "com.voxapps.vox.ocr.task"

    // --- Custom permissions (guard the exported receivers). SPEAK/TRIGGER_VOICE stay Commander-owned
    // (a different family — TRIGGER_VOICE is deliberately `normal`, for non-Vox external callers like
    // MacroDroid/Tasker). The four below are shared, declared once in this module's own manifest —
    // see the class doc comment above.
    const val SPEAK_PERMISSION = "com.voxapps.commander.permission.SPEAK"

    const val PERMISSION_COMMAND = "com.voxapps.vox.permission.COMMAND"
    const val PERMISSION_LLM_RESULT = "com.voxapps.vox.permission.LLM_RESULT"
    const val PERMISSION_OCR_RESULT = "com.voxapps.vox.permission.OCR_RESULT"
    const val PERMISSION_LLM_PROCESS = "com.voxapps.vox.permission.LLM_PROCESS"
    const val PERMISSION_SCHEMA_CHANGED = "com.voxapps.vox.permission.SCHEMA_CHANGED"
    const val PERMISSION_CAPABILITY_QUERY = "com.voxapps.vox.permission.CAPABILITY_QUERY"

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

    /**
     * Vox Hub's package name, for satellites that need to grant it read access to an export/import
     * attachment URI directly (see [VoxResult.attachmentUri]) — a standalone
     * [android.content.Context.grantUriPermission] call, independent of any Intent, since
     * export/import replies travel as ordered-broadcast `resultData` rather than a fresh Intent
     * (see [VoxDataTransferClient]), so the usual Intent-ClipData permission-grant path doesn't
     * apply here. Hardcoded rather than threaded through [VoxCommand] because [OP_EXPORT]/
     * [OP_IMPORT] are Hub-exclusive today — no other caller issues them — mirroring
     * [VISION_PACKAGE]'s role as the other hardcoded well-known-package constant in this file.
     */
    const val HUB_PACKAGE = "com.voxapps.hub"
}
