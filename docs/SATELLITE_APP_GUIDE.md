# Building a Vox Satellite App

This is a hands-on guide for adding a new **satellite app** to the VoxApps monorepo — an
independent Android app (its own `applicationId`, its own APK, works standalone with zero
dependency on VoxCommander) that *optionally* lights up when VoxCommander is also installed,
letting the user create/read its data by voice and get its data spoken back via TTS.

If you want the architecture overview instead of a build-it tutorial, see
[`docs/TECHNICAL_DOCUMENTATION.md` §19](TECHNICAL_DOCUMENTATION.md#19-vox-apps-ecosystem-cross-app-contract).

There is **no shared runtime state** between VoxCommander and a satellite. Everything crosses
process boundaries as JSON inside `Intent` extras over Android's native broadcast/activity
mechanisms, authenticated by signature-level permissions (not a token, not a server). The contract
types live in one small library module, `:core:ipc` — no networking, no ViewModels, just DTOs, a
handful of constants, and the small durable-delivery queue (§7).

---

## 1. Mental model

- **`:core:ipc`** — a contracts-only Gradle module (`com.voxapps.ipc` package). Depending on it in
  your `build.gradle.kts` gets you the DTO classes *and* (via manifest merger) every permission
  declaration you need. You never edit `:core:ipc` itself to add a satellite.
- **A satellite declares an exported `BroadcastReceiver`** for VoxCommander to talk to
  (`VoxCommandReceiver`, conventionally), guarded by a `signature`-level permission so only apps
  signed with the *same* release key can call it.
- **Capability advertising is manifest metadata, not a network call.** VoxCommander discovers your
  app by querying which installed apps have a receiver for a known broadcast action, then reads
  `<meta-data>` tags off that receiver to learn your domain/actions/label — zero code on
  VoxCommander's side needs to know your app exists in advance.
- **Two ways to turn a voice command into structured data**, per satellite:
  1. **Simple** (`needsExtractionPass = false`): your `OP_CREATE` handler receives raw text and
     does whatever it wants with it (e.g. Notes just stores the sentence verbatim).
  2. **Collapsed extraction** (`needsExtractionPass = true`): you hand VoxCommander a prompt
     template; it runs that prompt through its own LLM (one call, in its process) and delivers you
     back structured JSON to parse. Use this when your data model needs real field extraction
     (amount, date, category, line items, ...) — see §6.
- **A generic LLM hook** exists independent of the create/extraction flow — any satellite can fire
  an arbitrary prompt at VoxCommander's LLM and get an async JSON reply, for use cases that aren't
  "the user just spoke a command" (e.g. cleaning up OCR text from a scanned receipt, deduplicating
  categories). See §7. **Route it through `VoxLlmRequestQueue`, not a raw broadcast** — a plain
  `sendBroadcast` to a stopped/OEM-killed Commander is silently dropped with no error, which is exactly
  what happened to a real production notification-capture flow before this queue existed.
- **However the answer arrives, the record is made the same way.** Voice, a scan and a captured
  notification differ in what carries the text, not in what happens to it: read what the device can
  prove, decide who answers the rest, then write it, keep it for a person, or ask. That shape is
  `:core:recordflow`, and a satellite implements it rather than re-deriving it — which is also what
  makes the model *optional* rather than a second code path. See §12.
- **TTS is VoxCommander's job, not yours.** For `OP_READ`, you return text; VoxCommander decides to
  speak it. Your app never calls a "speak" hook itself.
- **`:core:datahygiene`** gives every satellite a shared way to clean garbage out of a record before
  it hits the database — auto-clean for LLM-derived saves, untouched for Hub import, confirm-first
  for manual UI edits (§6.6) — plus a generic, user-configurable duplicate-rule engine (§6.7).

---

## 2. Quickstart: minimal satellite from scratch

This walks through the *simple* path (`needsExtractionPass = false`) end to end, using the real
shape of `vox-notes` (the canonical minimal example) as the template.

### 2.1 Register the module

`settings.gradle.kts` (repo root):

```kotlin
include(":vox-yourapp")
```

Create `vox-yourapp/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.voxapps.yourapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voxapps.yourapp"
        minSdk = 29        // :core:ipc's own floor — can't go lower
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // Copy this block byte-for-byte from any existing vox-* app's build.gradle.kts.
    // keyAlias MUST be exactly "vox-apps" — see §8 (Security model) for why.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = "vox-apps"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:ipc"))   // the one line that matters for this guide
    // ... your app's own dependencies (Room, Compose, etc.)
}
```

That single `implementation(project(":core:ipc"))` line is doing more than it looks: it pulls in
the Kotlin DTO classes **and**, via Android's manifest merger, six `signature`-level `<permission>`
+ matching `<uses-permission>` declarations. You do not write any `<permission>` XML yourself.

### 2.1b Shared modules a satellite usually wants

`:core:ipc` is the only one this guide strictly requires. In practice a satellite takes several more,
and taking them is cheaper than re-solving what they cover:

| Module | What you get |
|---|---|
| `:core:design` | `VoxTheme` (dark × colored/Material You), the picklist family (`Picklist`, `GroupedPicklist`, `ServicePicklist`), `VoxColorPicker`, `VoxIconPicker`, `VoxCategoryFields`, `VoxFilterButton`/`VoxRangeBuckets` (a narrowed list saying what it is narrowed to), `VoxSemanticColors` (the colours kept out of the theme on purpose), `PaperTapField`, shared settings sections |
| `:core:preferences` | The settings plumbing every app repeats |
| `:core:backup` | Export/import, the biometric gate, snapshot-replace import, attachment zips |
| `:core:services` | `ServiceEntry`/`ProbeSpec` for anything with an API key or endpoint, and `SchemaRepo`/`RemoteSchema` if your app ships schemas |
| `:core:datahygiene` | Normalisation, duplicate rules, the WHEN/THEN re-map engine, merge-quality scoring (§6.6, §6.7) |
| `:core:textmatch` | Deterministic extraction (template skeletons, date/amount extractors, vocabulary classification, the two-field pre-parse a machine-sent message yields, and `AccountIdentifiers` — IBAN/card/masked-tail reading, the one extractor needing no vocabulary at all) plus fuzzy name matching |
| `:core:fieldmemory` | Two memories, both taught only by a human: learned field corrections over `:core:textmatch`'s diff, and a verdict per message template — two unanimous confirmations before it answers, permanent quarantine on the first disagreement. The verdict is an opaque string, so the app that owns the meaning maps it |
| `:core:recordflow` | The shape every record-creation path takes, and the ladder that makes the model optional (§12) |
| `:core:suggestions` | A proposal a record can hold until someone accepts it — the "offer it, don't apply it" half of that ladder. A target declares whether accepting *writes* to the record or *stages* into the screen's draft; the store refuses to write on a staging target rather than trusting each screen to remember |
| `:core:docread` | Reading a scanned document deterministically: rows and totals that prove each other before any model is asked |
| `:core:attachments`, `:core:location`, `:core:widget`, `:core:onboarding` | As needed |

If your app offers a choice between services that may need an API key or a reachability test, use
`ServicePicklist` rather than assembling a dropdown, a key field and a test button yourself — it
draws each part only when the selected entry declares it. See
[`TECHNICAL_DOCUMENTATION.md` §12](TECHNICAL_DOCUMENTATION.md#12-ui-architecture).

### 2.1c Wiring into the build and release machinery

Adding the module is not the same as being built and released. In order:

1. **CI needs nothing.** `.github/workflows/ci.yml` runs `./gradlew test` and `assembleDebug` across
   the whole project, so a newly included module is verified on the next push. Dependabot likewise
   covers it, since it reads the version catalogue.
2. **Release workflow** — copy any `release-<app>.yml` and change the app prefix, the module path and
   the `paths:` filter (`vox-yourapp/build.gradle.kts`). Three places must agree on the prefix: the
   workflow's `on.push.tags` pattern, the `app-prefix` input to `.github/actions/compute-release-tag`,
   and — if your app downloads DLC native libs — the `tagPrefix` it passes to `:core:nativelibs`'
   `NativeLibs`.
3. **Add it to the two follower workflows' trigger lists**, by workflow *name*:
   `deploy-fdroid.yml` and `update-readme-releases.yml` each enumerate the six release workflows
   under `workflow_run.workflows`. A new app that is not in those lists releases fine and is simply
   never mirrored to F-Droid nor added to the README table — a silent omission, so do it at the same
   time.
4. **Fastlane metadata** — `vox-yourapp/fastlane/metadata/android/en-US/` with
   `short_description.txt`, `full_description.txt` and `images/phoneScreenshots/`. F-Droid metadata is
   generated from these by `scripts/sync_fdroid_metadata.sh`.
5. **Schemas, if any** — create `remote-schemas/yourapp/`, add a `copyShippedSchemas` task copying
   `<yours>/*.json` + `shared/*.json` into `src/main/assets/schemas/`, wire it into `preBuild`, and
   set `SchemaRepo.appFolder` in your `Application` before any registry starts. The folder is the
   list; never name individual files in the build script.
6. **Signing** — the `keyAlias = "vox-apps"` block above, byte-for-byte. See §8.

Bump `versionCode`/`versionName` and push a change to `vox-yourapp/build.gradle.kts` to cut the first
release. See [`BUILD_AND_RELEASE.md`](BUILD_AND_RELEASE.md) for what happens next, including the one
rule worth knowing in advance: don't edit workflow files while a release is building.

### 2.2 Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".YourApplication"
        android:label="@string/app_name"
        android:theme="@style/Theme.YourApp">

        <activity
            android:name=".YourMainActivity"
            android:exported="true">
            <!-- Standalone launcher — your app must work with zero VoxCommander installed. -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!--
            OPTIONAL Vox integration. No manifest <permission>/<uses-permission> declarations
            needed here — they arrive automatically via :core:ipc's manifest merge.
        -->
        <receiver
            android:name=".receiver.VoxCommandReceiver"
            android:exported="true"
            android:permission="com.voxapps.vox.permission.COMMAND">
            <intent-filter>
                <action android:name="com.voxapps.action.VOX_COMMAND" />
            </intent-filter>

            <!-- Capability advertising: VoxCommander reads these at scan time. Zero config
                 needed on VoxCommander's side — this is the entire "registration" step. -->
            <meta-data android:name="com.voxapps.vox.domain" android:value="yourdomain" />
            <meta-data android:name="com.voxapps.vox.actions" android:value="create,read,get_schema" />
            <meta-data android:name="com.voxapps.vox.label" android:value="Your App" />
            <!-- Optional: a one-line hint appended to VoxCommander's NLU prompt so it can
                 correctly extract domain-specific fields from an utterance before your app
                 ever sees it. See §5. Omit the tag entirely if you don't need this. -->
            <meta-data
                android:name="com.voxapps.vox.nluHint"
                android:value="If the user names X, put it in field Y; otherwise Y=null." />
        </receiver>

    </application>
</manifest>
```

`android:permission="com.voxapps.vox.permission.COMMAND"` is the entire authentication story for
this receiver: Android refuses to deliver the broadcast unless the sender holds a permission with
`protectionLevel="signature"` matching your app's signing certificate — which VoxCommander does,
*provided* you both used `keyAlias = "vox-apps"` in a release build (§8).

`android:exported="true"` looks alarming but is safe specifically *because* of the permission guard
— any app, first- or third-party, can send you an `ACTION_VOX_COMMAND` broadcast, but only ones
signed with your same key ever get past the permission check on delivery.

### 2.3 Implement `VoxCommandReceiver`

```kotlin
package com.voxapps.yourapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.ipc.VoxSatelliteSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoxCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_COMMAND) return
        val command = VoxCommand.fromJson(intent.getStringExtra(VoxIpc.EXTRA_PAYLOAD)) ?: return
        val container = (context.applicationContext as YourApplication).container

        when (command.op) {
            // Handshake — fully synchronous, no DB touch. VoxCommander's discovery ping.
            VoxIpc.OP_PING -> {
                setResult(android.app.Activity.RESULT_OK, VoxResult(ok = true, text = "pong").toJson(), null)
            }

            // Fire-and-forget write. goAsync() extends the receiver's lifetime past onReceive's
            // return so the coroutine can actually finish before the process is free to be killed.
            VoxIpc.OP_CREATE -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val text = command.text ?: return@launch
                        container.yourRepository.createFromVoice(text, command.category)
                    } finally {
                        pending.finish()
                    }
                }
            }

            // Read-and-speak. IMPORTANT: once goAsync() has been called, you MUST reply via
            // pending.setResultData(...), NOT the inherited setResult() — that throws "Call while
            // result is not pending" once you're outside onReceive's synchronous window.
            VoxIpc.OP_READ -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val items = container.yourRepository.readRecent(command.limit ?: 5)
                        val text = items.joinToString("; ") { it.summary }
                        pending.setResultData(VoxResult(ok = true, text = text).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            // VoxCommander fetches this once (not per voice command) and caches it. Returning
            // needsExtractionPass=false here means OP_CREATE above receives the raw utterance
            // as-is — no LLM extraction pass happens for this satellite. See §6 to opt into that.
            VoxIpc.OP_GET_SCHEMA -> {
                val schema = VoxSatelliteSchema(needsExtractionPass = false)
                setResult(android.app.Activity.RESULT_OK, VoxResult(ok = true, text = schema.toJson()).toJson(), null)
            }
        }
    }
}
```

Register it in the manifest exactly as shown in §2.2. That's a complete, working satellite: install
both apps (signed with the same `vox-apps` alias, or both debug builds sharing Android Studio's
default debug keystore), open VoxCommander's Integrations screen, hit Refresh, and your app should
appear with a green "first-party" indicator.

### 2.4 Verify it manually without VoxCommander

You can exercise your receiver directly with `adb shell am broadcast` while developing, without
VoxCommander installed at all — useful for isolating bugs to your side of the contract:

```bash
adb shell am broadcast \
  -a com.voxapps.action.VOX_COMMAND \
  -p com.voxapps.yourapp \
  --es com.voxapps.extra.PAYLOAD '{"op":"create","text":"test note"}'
```

Note this bypasses the permission check (a shell-issued broadcast isn't signature-checked the same
way), so it proves your JSON parsing/dispatch logic works but not the cross-app signature story —
for that, you need a real second app (VoxCommander or a throwaway test sender) installed with a
matching signature.

---

## 3. The `VoxIpc` constants reference

Everything below lives in `com.voxapps.ipc.VoxIpc` (`core/ipc/src/main/java/com/voxapps/ipc/VoxIpc.kt`).
Treat this object as the single source of truth — don't hardcode these strings in your own code,
import the constants.

### Actions

| Constant | Value | Direction |
|---|---|---|
| `ACTION_COMMAND` | `com.voxapps.action.VOX_COMMAND` | Commander → satellite |
| `ACTION_SPEAK` | `com.voxapps.action.SPEAK` | any app → Commander (TTS) |
| `ACTION_LLM_PROCESS` | `com.voxapps.action.LLM_PROCESS` | satellite → Commander |
| `ACTION_LLM_RESULT` | `com.voxapps.action.LLM_RESULT` | Commander → satellite |
| `ACTION_OCR_RESULT` | `com.voxapps.action.OCR_RESULT` | Vision → satellite |
| `ACTION_SCHEMA_CHANGED` | `com.voxapps.action.SCHEMA_CHANGED` | satellite → Commander (push) |
| `ACTION_CAPABILITY_QUERY` | `com.voxapps.action.CAPABILITY_QUERY` | any first-party app → Commander |

### Extras

| Constant | Value | Carries |
|---|---|---|
| `EXTRA_PAYLOAD` | `com.voxapps.extra.PAYLOAD` | `VoxCommand`/`VoxResult` JSON |
| `EXTRA_RESULT` | `com.voxapps.extra.RESULT` | (result-envelope alias, ordered-broadcast paths) |
| `EXTRA_QUERY` | `com.voxapps.extra.QUERY` | text to speak, for `ACTION_SPEAK` |
| `EXTRA_LLM_PAYLOAD` | `com.voxapps.extra.LLM_PAYLOAD` | `VoxLlmRequest`/`VoxLlmResult` JSON |
| `EXTRA_OCR_PAYLOAD` | `com.voxapps.extra.OCR_PAYLOAD` | `VoxOcrRequest`/`VoxOcrResult` JSON |
| `EXTRA_SCHEMA_PAYLOAD` | `com.voxapps.extra.SCHEMA_PAYLOAD` | `VoxSatelliteSchema` JSON (push path) |
| `EXTRA_SOURCE_PACKAGE` | `com.voxapps.extra.SOURCE_PACKAGE` | sender's own package name (self-declared, then verified) |
| `EXTRA_SELECTED_DATE` | `com.voxapps.extra.SELECTED_DATE` | (Calendar-specific day-scoped read) |
| `EXTRA_EXPENSE_ID` | `com.voxapps.extra.EXPENSE_ID` | (Expenses-specific deep-link extra) |
| `EXTRA_EDIT_NOTE_ID` | `com.voxapps.notes.EXTRA_EDIT_NOTE_ID` | (Notes-specific deep-link extra) |

### `VoxCommand.op` values

| Constant | Value | Meaning |
|---|---|---|
| `OP_CREATE` | `create` | Fire-and-forget write from a voice command |
| `OP_READ` | `read` | Request-response: return text for VoxCommander to speak |
| `OP_PING` | `ping` | Handshake — discovery/health-check |
| `OP_GET_SCHEMA` | `get_schema` | Request-response: return your `VoxSatelliteSchema` |
| `OP_EXPORT` | `export` | Request-response: return your data as JSON (Vox Hub backup) |
| `OP_IMPORT` | `import` | Fire/request-response: restore data from JSON (Vox Hub restore) |
| `OP_SYNC_EXPORT` / `OP_SYNC_MERGE` | `sync_export` / `sync_merge` | Request-response: P2P device-sync delta out / delta in (Vox Hub) |
| `OP_GET_FIELD_SCHEMA` | `get_field_schema` | Request-response: return your editable-field form schema (VoxConnect Bridge) |
| `OP_MEDIA_CONTROL` | `media_control` | Request-response: media-session relay, Hub → Commander only |

The sync/media ops carry their parameters in `VoxCommand` fields (`since`/`scopeNames`/`mediaAction`
— see §4); their full semantics live in
[`TECHNICAL_DOCUMENTATION.md`](TECHNICAL_DOCUMENTATION.md) and the `VoxIpc` doc comments.

`VoxCommand.exportScope` values (only relevant for `OP_EXPORT`): `EXPORT_SCOPE_SETTINGS = "settings"`,
`EXPORT_SCOPE_DATA = "data"`, `EXPORT_SCOPE_BOTH = "both"`. `VoxCommand.importMode` values (only
relevant for `OP_IMPORT`): `IMPORT_MODE_FULL_OVERRIDE = "full_override"`, `IMPORT_MODE_MERGE =
"merge"`, `IMPORT_MODE_ADDITIVE = "additive"` — null defaults to merge on the receiving end. The
receiving-end semantics (`VoxSnapshotReplaceImporter`): `FULL_OVERRIDE` deletes every pre-existing
item; `MERGE` deletes only pre-existing items created at or before the export's `exportedAt`
cutoff; `ADDITIVE` deletes nothing.

### Manifest meta-data keys (capability advertising)

| Constant | Value | Read by |
|---|---|---|
| `META_DOMAIN` | `com.voxapps.vox.domain` | `VoxAppsDiscovery.discover()` — the NLU domain your receiver owns |
| `META_ACTIONS` | `com.voxapps.vox.actions` | comma-separated ops you support, e.g. `create,read,get_schema` |
| `META_LABEL` | `com.voxapps.vox.label` | human-readable name shown in VoxCommander's Integrations screen |
| `META_NLU_HINT` | `com.voxapps.vox.nluHint` | one-line addition to VoxCommander's shared NLU prompt (§5) |
| `META_OCR_TASK` | `com.voxapps.vox.ocr.task` | (only if you receive OCR results — Vision's dispatcher discovery) |

### Permissions

| Constant | Value | Declared in | Guards |
|---|---|---|---|
| `PERMISSION_COMMAND` | `com.voxapps.vox.permission.COMMAND` | `:core:ipc` (shared) | your `VoxCommandReceiver` |
| `PERMISSION_LLM_RESULT` | `com.voxapps.vox.permission.LLM_RESULT` | `:core:ipc` (shared) | your `LlmResultReceiver` |
| `PERMISSION_OCR_RESULT` | `com.voxapps.vox.permission.OCR_RESULT` | `:core:ipc` (shared) | your `OcrResultReceiver` (if used) |
| `PERMISSION_LLM_PROCESS` | `com.voxapps.vox.permission.LLM_PROCESS` | `:core:ipc` (shared) | Commander's `LlmHookReceiver` |
| `PERMISSION_SCHEMA_CHANGED` | `com.voxapps.vox.permission.SCHEMA_CHANGED` | `:core:ipc` (shared) | Commander's `SchemaChangedReceiver` |
| `PERMISSION_CAPABILITY_QUERY` | `com.voxapps.vox.permission.CAPABILITY_QUERY` | `:core:ipc` (shared) | Commander's `CapabilityQueryReceiver` |
| `SPEAK_PERMISSION` | `com.voxapps.commander.permission.SPEAK` | VoxCommander's own manifest (not shared) | Commander's TTS receiver |

All six `PERMISSION_*` above are `protectionLevel="signature"` — granted only to apps signed with an
identical certificate. They are declared once in `core/ipc/src/main/AndroidManifest.xml` and folded
into your app automatically by manifest merger the moment you add `implementation(project(":core:ipc"))`.
You never write a `<permission>` tag yourself for any of these.

### Well-known package/class constants

```kotlin
VoxIpc.VISION_PACKAGE          // "com.voxapps.vision"
VoxIpc.VISION_ACTIVITY_CLASS   // "com.voxapps.vision.VisionActivity"
// Always launch Vision by this class name. Vision also exposes a second launcher entry (the
// "Vox LiveView" activity-alias) for its own reading mode — it is not part of the IPC contract,
// and an OCR request always lands in the scan flow regardless of which icon a person used last.
VoxIpc.HUB_PACKAGE             // "com.voxapps.hub"
VoxIpc.NOTES_PACKAGE           // "com.voxapps.notes"
VoxIpc.EXPENSES_PACKAGE        // "com.voxapps.expenses"
VoxIpc.CALENDAR_PACKAGE        // "com.voxapps.calendar"
VoxAppsDiscovery.COMMANDER_PACKAGE  // "com.voxapps.commander" (different file)
```

---

## 4. The DTOs

All are plain Kotlin `data class`es with `toJson(): String` / `fromJson(json: String?): T?`
(the latter using `org.json.JSONObject`, lenient — returns `null` on blank/invalid input rather than
throwing). Import them from `com.voxapps.ipc`.

```kotlin
data class VoxCommand(
    val op: String,
    val text: String? = null,
    val title: String? = null,
    val category: String? = null,
    val limit: Int? = null,
    val domain: String? = null,
    val exportScope: String? = null,
    val includeSecrets: Boolean = false,
    val includePhotos: Boolean = false,
    val dateFrom: Long? = null,   // day-scoped OP_READ (Calendar's day-tap summary)
    val dateTo: Long? = null,
    val since: Long? = null,              // OP_SYNC_EXPORT: only entries changed after this;
                                          // null/0 = everything (first-ever sync with a peer)
    val scopeNames: List<String>? = null, // OP_SYNC_EXPORT: category/layer *names* (not ids —
                                          // ids aren't stable across devices) to restrict to
    val mediaAction: String? = null,      // OP_MEDIA_CONTROL: "status"/"play"/"pause"/"next"/"prev"
    val importMode: String? = null        // OP_IMPORT: IMPORT_MODE_FULL_OVERRIDE/MERGE/ADDITIVE;
                                          // null defaults to "merge" on the receiving end
)

data class VoxResult(
    val ok: Boolean,
    val text: String,              // payload when ok, or a user-facing message (e.g. "locked") when not
    val attachmentUri: String? = null,          // content:// URI, e.g. OP_EXPORT's receipt/attachments zip
    val secondaryAttachmentUri: String? = null  // OP_EXPORT only: a second zip, for a domain that already
                                                 // uses attachmentUri for something else (e.g. Expenses'
                                                 // receipts zip) but also has a :core:attachments bundle
)

data class VoxSatelliteSchema(
    val needsExtractionPass: Boolean,   // required — fromJson() returns null if this key is missing
    val promptTemplate: String = "",
    val fieldSchemaVersion: Int = 0,
    val taskId: String = ""
) {
    companion object { const val INPUT_PLACEHOLDER = "{{INPUT}}" }
    fun buildPrompt(input: String): String  // promptTemplate.replace(INPUT_PLACEHOLDER, input)
}

data class VoxLlmRequest(
    val sourcePackage: String,     // required — Commander uses it as the reply's explicit-intent target
    val task: String,              // opaque, caller-owned task id
    val promptText: String,
    val data: List<String> = emptyList(),
    val attachmentUri: String? = null   // only meaningful if VoxCapabilityClient.isMultimodal() first
)

data class VoxLlmResult(
    val task: String,
    val status: String,            // STATUS_SUCCESS | STATUS_ERROR
    val rawJson: String? = null,
    val error: String? = null,
    val input: String? = null      // what Commander put to the model, echoed back — set ONLY on the
                                   // collapsed path, where Commander filled your cached
                                   // promptTemplate itself and you never saw the text. Null on the
                                   // generic hook, where YOU composed the request and your own
                                   // queue still holds the input (VoxLlmRequestQueue.originalInput)
)

data class VoxOcrRequest(
    val sourcePackage: String,
    val task: String,                    // opaque, caller-owned — echoed back verbatim
    val hint: String? = null,            // cosmetic free text Vision's UI may show
    val returnToCallerOnComplete: Boolean = false,  // relaunch the caller's own task when Vision finishes
    val imageUri: String? = null,        // headless: OCR this existing content:// URI, no camera UI —
                                         // caller must grantUriPermission(VISION_PACKAGE, ...) first
    val produceOCR: Boolean = true,      // false = capture/crop only, rawText comes back null
    val captureMode: String = CAPTURE_MODE_SINGLE,  // CAPTURE_MODE_SINGLE | CAPTURE_MODE_BATCH
                                         // (several shots, one record each) | CAPTURE_MODE_STITCH
                                         // (several shots, ONE record, live per-shot OCR with a
                                         // text-continuity check between shots)
    val tableMode: Boolean = false       // declares the document tabular: Vision appends a
                                         // "--- [table reconstruction] ---" section after the plain
                                         // OCR text (the plain part stays what prompts/regexes read;
                                         // the marker-delimited section is the additive table view),
                                         // and the plain text is line-broken at printed-row boundaries
)

data class VoxOcrResult(
    val task: String,
    val status: String,                  // STATUS_SUCCESS | STATUS_ERROR
    val rawText: String? = null,         // Stitch: all accepted shots' text already joined, each join
                                         // carrying a literal "--- [photo stitch seam …] ---" marker;
                                         // table mode: ends with the "--- [table reconstruction] ---"
                                         // section — consumers that regex the plain text must cut at
                                         // the marker; Batch: always null
    val imageUris: List<String> = emptyList(),  // full-res photo(s) — one element for a single shot,
                                                // several for Batch/Stitch
    val rawTexts: List<String> = emptyList(),   // Batch: per-photo OCR text, same index as imageUris
    val aiImageUri: String? = null,      // separately downscaled copy for LLM attachment, or null
    val error: String? = null
)
```

A locking pattern you'll want if your app has an app-lock/biometric feature: check
`settings.isBiometricRequired && !sessionManager.isSessionValid(...)` **before touching your
database** in `OP_READ`/`OP_EXPORT`, and reply `VoxResult(ok = false, text = "<locked message>")`
instead — Commander can speak that message back to the user rather than silently failing.

---

## 5. `nluHint`: teaching the shared NLU prompt about your domain

VoxCommander runs one shared LLM prompt for every voice command (not one per domain). If your
satellite needs a specific field extracted a certain way (e.g. "if the user names a target
list/category, put it in the category field"), declare it once via the `com.voxapps.vox.nluHint`
meta-data tag on your `VoxCommandReceiver` (§2.2). VoxCommander's `PromptProvider.buildSatelliteHints()`
collects every installed satellite's hint and appends one line per app:

```
Domain-specific extraction:
- yourdomain: If the user names X, put it in field Y; otherwise Y=null.
```

This is read from the **already-refreshed** `VoxSatelliteRegistry` in-memory snapshot, not a fresh
scan — a hint only shows up in the prompt after VoxCommander has refreshed its registry at least
once since your app was installed (happens automatically at VoxCommander startup, or via the
Integrations screen's Refresh button).

Keep the hint to one line and genuinely domain-specific — this string is appended to *every* voice
command's prompt regardless of domain, so it costs a little prompt size for every user, every time,
whether or not they're actually talking to your app.

---

## 6. Collapsed extraction: `needsExtractionPass = true`

Use this when your data model needs real field extraction from a spoken sentence — amounts, dates,
categories, line items — rather than just storing the raw text. Instead of your app writing a second
LLM call itself, you hand VoxCommander a prompt template; it runs the extraction in its own process
(one LLM call total, not two) and delivers you back JSON to parse.

### 6.1 Declare the schema

```kotlin
VoxIpc.OP_GET_SCHEMA -> {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val categoryNames = container.yourRepository.categories.first().map { it.name }
            val schema = VoxSatelliteSchema(
                needsExtractionPass = true,
                promptTemplate = YourParsePromptBuilder.buildTemplate(categoryNames, settings.language),
                fieldSchemaVersion = GeneratedParsedSchema.VERSION,
                taskId = LlmTasks.YOUR_PARSE_TASK
            )
            pending.setResultData(VoxResult(ok = true, text = schema.toJson()).toJson())
        } finally {
            pending.finish()
        }
    }
}
```

VoxCommander fetches and caches this once (not per voice command) — via its Integrations screen's
Refresh button, or proactively if you push a change (§6.5). `promptTemplate` must contain the
literal string `{{INPUT}}` (`VoxSatelliteSchema.INPUT_PLACEHOLDER`) exactly once, at the point where
the user's utterance should be substituted.

**Important nuance**: `promptTemplate` is a hand-written prompt string — reasoning rules, examples,
your field-format description — not something auto-generated from an annotation. The KSP mechanism
in §6.2 exists to keep that hand-written prompt *honest* against your actual parser's expected
shape (a version marker you bump when you change fields), not to generate the prompt text itself.
You still write the prompt by hand, the same way you'd write any other LLM prompt in this codebase.

### 6.2 `@VoxExtractionSchema` — the versioning aid

Add to `build.gradle.kts`:

```kotlin
plugins {
    // ...
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":core:schema-annotations"))   // exposes @VoxExtractionSchema
    ksp(project(":core:schema-processor"))                 // runs the processor at compile time
}
```

Annotate your parser's target data class (the shape you expect the LLM's JSON reply to match):

```kotlin
@VoxExtractionSchema(version = 1)
data class Parsed(
    val title: String?,
    val totalAmount: Double,
    val currency: String?,
    val category: String?,
    val date: String?,   // YYYY-MM-DD
    val items: List<ParsedItem>
)
```

The annotation is **class-level only** (`AnnotationTarget.CLASS`) — there's no per-field annotation
for descriptions; the processor reflects the primary constructor's parameter names/types/nullability
directly. It generates `Generated<ClassName>Schema` (e.g. `GeneratedParsedSchema`) under
`build/generated/ksp/.../`:

```kotlin
object GeneratedParsedSchema {
    const val VERSION: Int = 1
    const val FIELD_SCHEMA_JSON: String = """[{"name":"title","type":"String","nullable":true}, ...]"""
}
```

Only `VERSION` is actually consumed anywhere (stamped into `fieldSchemaVersion` as a cache-staleness
marker) — `FIELD_SCHEMA_JSON` exists as a machine-readable snapshot for code review/diffing, not a
runtime dependency. **Bump the `version` argument whenever you add/remove/rename a field on the
annotated class**, so a stale cached schema on VoxCommander's side is at least visibly out of date.

Computed properties (anything not a primary-constructor parameter) are silently excluded from the
generated schema — only real constructor fields are reflected.

### 6.3 How VoxCommander runs it

`SatelliteHandler.create()` checks `VoxSatelliteRegistry.cachedSchema(pkg)?.needsExtractionPass`; if
true, it calls `schema.buildPrompt(intent.toDecompositionText())` and runs that prompt through its
own LLM (`container.llmHookEngineSelector.run(prompt)`) in its own process — your `OP_CREATE`
handler is **not** invoked for this path at all. The result is delivered back to you as a
`VoxLlmResult` stamped with `task = schema.taskId`, over the exact same wire shape as the generic
LLM hook (§7) — so the receiver you'd write to consume it is identical either way.

### 6.4 Consuming the result

```kotlin
// LlmResultReceiver.kt
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != VoxIpc.ACTION_LLM_RESULT) return
    val result = VoxLlmResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD)) ?: return

    when (result.task) {
        LlmTasks.YOUR_PARSE_TASK -> {
            if (result.status != VoxLlmResult.STATUS_SUCCESS || result.rawJson == null) return
            val parsed = YourParseResultParser.parse(result.rawJson) ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try { container.yourRepository.createFromParsed(parsed) } finally { pending.finish() }
            }
        }
        else -> Unit  // unknown/foreign task id — ignore
    }
}
```

Register this receiver in your manifest guarded by `com.voxapps.vox.permission.LLM_RESULT` (same
pattern as §2.2's `VoxCommandReceiver`, different action: `com.voxapps.action.LLM_RESULT`).

The body above writes the record inline, which is the shortest thing that works and the wrong place
to leave it once a satellite has more than one way to make a record. What replaces
`createFromParsed` is `RecordFlow.deliver` — see §12.

Note `result.input`. On this path Commander composed the prompt from your cached template, so it is
the only side that saw the text; the echo is how you get it back, and it is what lets a rule on the
device check an answer against the question it answered. On the generic hook (§7) it is null by
design — there *you* composed the request, and your own durable queue still holds the input under
the request id (`VoxLlmRequestQueue.originalInput`, which must be read before `markFulfilled`
deletes the row).

Define your own task-id constants somewhere local (they're entirely opaque to VoxCommander — it
only ever echoes them back):

```kotlin
object LlmTasks {
    const val YOUR_PARSE_TASK = "YOUR_PARSE_TASK"
}
```

### 6.5 Push instead of pull (optional)

Rather than waiting for VoxCommander to re-fetch your schema, push a change proactively whenever
something that affects your `promptTemplate` changes (e.g. your category list):

```kotlin
VoxDataTransferClient.pushSchemaChanged(context, schema)
```

This sends `ACTION_SCHEMA_CHANGED` as an explicit intent targeting VoxCommander, guarded by
`PERMISSION_SCHEMA_CHANGED`, with your package name self-stamped in `EXTRA_SOURCE_PACKAGE` (a plain
broadcast doesn't expose caller identity, so VoxCommander re-verifies that claim via
`PackageManager.checkSignatures` before trusting it — see §8).

### 6.6 Data hygiene: cleaning records before insert

`org.json.JSONObject.optString(key)` (no fallback arg) silently turns a genuine JSON `null` value
into the **literal string `"null"`** — `JSONObject` stores JSON null as the `JSONObject.NULL`
sentinel, whose `toString()` returns `"null"`, and `optString` stringifies whatever `opt()` returns.
A well-formed LLM reply (`"vendor": null`) and a malformed one (`"vendor": "null"`) both corrupt your
DB identically, with no exception to catch. The same class of bug shows up any time a field is
extracted without a garbage guard — a stray `"."` or `";"` from a noisy LLM reply, an unguarded manual
text field, etc.

**`:core:datahygiene`** (`com.voxapps.datahygiene`) is the shared fix, used by every satellite that
does its own JSON extraction and/or record editing (vox-expenses, vox-calendar, vox-notes). Depend on
it the same way as `:core:ipc`:

```kotlin
implementation(project(":core:datahygiene"))
```

**At the JSON-parsing boundary**, replace bare `optString`/`isNull` checks with the shared extension:

```kotlin
import com.voxapps.datahygiene.optCleanString

val vendor = o.optCleanString("vendor")          // null for: absent key, JSON null, "null" (any case), blank, or pure punctuation
val title  = o.optCleanString("title", fieldName = "title", recordLabel = "Expense") // adds a debug log line when something gets discarded
```

**At the record level**, implement one `RecordSanitizer<T>` per entity — this is the "virtual class
every DB-operation class implements" that the field-level guard alone doesn't give you, because it
also needs to answer "which fields are dirty, and what's the actual offending text?" (used to show a
specific message, not a generic one — see below):

```kotlin
import com.voxapps.datahygiene.DirtyField
import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.datahygiene.RecordSanitizer

object YourEntitySanitizer : RecordSanitizer<YourEntity> {
    override fun sanitize(record: YourEntity): YourEntity = record.copy(
        someNullableField = FieldCleaner.clean(record.someNullableField, "someNullableField", "YourEntity#${record.id}")
        // for a non-nullable String field, use FieldCleaner.cleanRequired(value, fallback, ...) instead
    )

    override fun dirtyFields(record: YourEntity): List<DirtyField> = listOfNotNull(
        FieldCleaner.dirtyValue(record.someNullableField)?.let { DirtyField("someNullableField", it) }
    )
}
```

`FieldCleaner.dirtyValue(value)` returns the actual trimmed offending text (e.g. `null`, `.`) if the
field is garbage, or `null` if it's fine — including if it's merely blank. A field is **never** flagged
just for being empty, and the "null" check is a **whole-string** match only: a title like `"Meeting
about Null Island"` or `"null and void, discuss tomorrow"` is left alone — only a field whose entire
trimmed content is exactly `null` (any case) counts as garbage.

**The three-way save-source contract** — this is the part that answers "clean automatically, or ask
first?" — lives in one shared function, `decideForSave`, so you never re-implement the branching:

```kotlin
import com.voxapps.datahygiene.RecordSource
import com.voxapps.datahygiene.SaveDecision
import com.voxapps.datahygiene.decideForSave

when (val decision = YourEntitySanitizer.decideForSave(candidate, source)) {
    is SaveDecision.Proceed        -> save(decision.record)   // LLM: already auto-cleaned. Hub import: untouched, as-is.
    is SaveDecision.ConfirmCleanup -> showCleanupDialog(decision.original, decision.dirtyFields) // MANUAL_UI only, only when dirtyFields is non-empty
}
```

`RecordSource` is `LLM` / `HUB_IMPORT` / `MANUAL_UI`:

| Source | Behavior |
| --- | --- |
| `LLM` | Always `sanitize()`s and proceeds — no prompt, since there's no human to ask mid-broadcast. |
| `HUB_IMPORT` | Always proceeds **untouched** — another install already validated this data; silently rewriting it on import would be its own bug. |
| `MANUAL_UI` | Proceeds untouched if clean; if `dirtyFields` is non-empty, returns `ConfirmCleanup` (carrying that list) instead of saving so the UI can ask the user to accept auto-clean or cancel and fix it themselves. |

**Showing the actual offending value, not a generic message.** The confirm dialog should tell the user
*which* field and *what* text tripped the guard — not just "some fields look wrong." Render each
`DirtyField` with its raw `value` highlighted (e.g. in red) so it's unmistakable at a glance:

```kotlin
@Composable
fun CleanupDialog(dirtyFields: List<DirtyField>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clean up before saving?") },
        text = {
            Column {
                dirtyFields.forEach { field ->
                    Text(
                        buildAnnotatedString {
                            append("${fieldLabel(field.fieldKey)}: ")
                            withStyle(SpanStyle(color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)) {
                                append(field.value) // the actual offending text, e.g. "null" or "."
                            }
                        }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Auto-clean") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
```

`field.fieldKey` is whatever string you passed into `DirtyField(...)` in your `dirtyFields()`
implementation (e.g. `"vendor"`) — map it to a localized label via your app's own `LanguageManager`
before display; `:core:datahygiene` itself has no localization dependency.

Where to call `decideForSave` from:
- **LLM path** (§6.4's receiver, or your `OP_CREATE` handler): call it right before the repository
  insert, with `RecordSource.LLM`. If your repository method takes individual named fields rather
  than a full entity (common for `addParsed*`-style calls), it's fine to call `FieldCleaner.clean`
  directly on each field instead of constructing a throwaway entity just to run it through
  `decideForSave` — the LLM branch always auto-cleans anyway, so the two are equivalent.
- **Manual UI save button**: build the candidate record from current UI state, call `decideForSave`
  with `RecordSource.MANUAL_UI`, and hold the `ConfirmCleanup` case in local dialog state (mirror
  whatever nullable-state-holds-dialog-target pattern your screen already uses for delete
  confirmation). Accept → `sanitize()` then save; Cancel → dismiss, keep editing.
- **Hub import handler**: don't call it at all. Import already goes straight to the repository.

This gating happens in the **caller**, never inside the shared repository's `add*`/`update*` methods
— those are used by both manual saves and Hub import with no way to tell them apart internally, so
sanitizing inside the repository would incorrectly also rewrite imported data.

### 6.7 Duplicate detection (`RuleBasedDuplicateChecker`)

`:core:datahygiene` also ships a generic, entity-agnostic duplicate-rule engine — the same model email
clients use for filters: register a `RuleField<T>` per comparable field on your entity, let the user
build named `DuplicateRule`s (a set of fields combined with their own AND/OR), and combine however many
rules exist with one global AND/OR. `RuleBasedDuplicateChecker<T>` (a `DuplicateChecker<T>`) evaluates
it; an empty-fields rule or an empty rule list never matches, rather than throwing.

```kotlin
import com.voxapps.datahygiene.RuleField
import com.voxapps.datahygiene.DuplicateRule
import com.voxapps.datahygiene.RuleBasedDuplicateChecker
import com.voxapps.datahygiene.RuleCombinator
import com.voxapps.datahygiene.stringField   // FieldCleaner-normalized, optional fuzzy matching
import com.voxapps.datahygiene.exactField    // null-safe ==
import com.voxapps.datahygiene.timeWindowField // abs(delta) <= windowMillis

class YourEntityRuleFields(fuzzyMatchEnabled: Boolean, timeWindowMillis: Long) {
    val all: List<RuleField<YourEntity>> = listOf(
        stringField("title", "field_title", fuzzyMatchEnabled, ::FuzzyNameMatcher) { it.title },
        exactField("amount", "field_amount") { it.amount },
        timeWindowField("dateTime", "field_date_time", timeWindowMillis) { it.dateTime }
    )
}
```

Everything entity-specific — the field registry, where rules are persisted (vox-expenses uses a Room
entity/DAO, migrated in with default rules seeded to reproduce whatever hardcoded behavior it's
replacing), and the rule-list/edit UI — is your app's own responsibility; the core module only supplies
the field/rule/evaluation contracts. See `vox-expenses/.../data/ExpenseRuleFields.kt`,
`DuplicateRuleEntity.kt`/`DuplicateRuleDao.kt`, and `ui/settings/DuplicateRulesSection.kt` for a
complete worked example, including a Room migration that seeds defaults on both upgrade and fresh
install.

**Keep genuinely-invariant fields out of the opt-in rule list.** A rule's fields are entirely
user-selectable — nothing stops a user (or a hand-edited default rule) from building a rule that
doesn't check a field your domain considers non-negotiable. If two records can *never* be duplicates
when some field differs (e.g. vox-expenses' `direction` — an incoming top-up and an outgoing payment of
the same amount are two different real transactions, not a duplicate, confirmed by a real on-device
report), enforce that as an unconditional check in your own wrapper around
`RuleBasedDuplicateChecker.isDuplicateOf` — before consulting the rules at all — rather than trusting
every rule to remember to include it.

**Picking a merge winner** (`recordScore` / `RecordProvenance`, same file) — a second, independent
building block for *which* record's data wins once two are confirmed duplicates, instead of always
preferring whichever arrived first: `recordScore(manuallyEdited, provenance, completenessFields)`
scores `10_000` for a human-edited record (an unconditional pin), plus your entity's own
`RecordProvenance.trustTier` (a capture-source ranking you define — e.g. vox-expenses'
`ExpenseSource.MANUAL(400) > SCAN(300) > NOTIFICATION(200) > VOICE(100)`), plus how many of the fields
you pass in `completenessFields` are non-null. Not the same concept as `RecordSource` (§6.6) —
`RecordSource` routes a save through the sanitize-or-confirm policy, `RecordProvenance` ranks data
trustworthiness — the names are deliberately different to avoid conflating them. See
`vox-expenses/.../data/ExpenseDataScore.kt` for a complete worked example, including how the score
feeds both the actual field-merge logic and a review UI's default "keep" selection.

---

## 7. The generic LLM hook (outside the create/extraction flow)

For anything that isn't "the user just spoke a command to me" — e.g. cleaning up noisy OCR text, or
deduplicating a list your app already has — fire an arbitrary prompt at VoxCommander directly. **Route
this through `VoxLlmRequestQueue` (`:core:ipc`), not a raw `sendBroadcast`** — a plain broadcast to a
"stopped" Commander (force-stopped, or killed by an OEM background-management feature) is silently
dropped by the OS before its receiver ever runs, with no crash and no error to catch; this bit a real
production notification-capture flow (see
[Durable delivery: the pending-request queue](TECHNICAL_DOCUMENTATION.md#durable-delivery-the-pending-request-queue-voxllmrequestqueue)
for the full "why"). The queue fixes it two ways: it sets `FLAG_INCLUDE_STOPPED_PACKAGES` so the
broadcast wakes a stopped Commander, and it persists the request first so a periodic worker can retry
it if no reply ever comes. It also dedupes: there is only ever one pending row per
(source, target, task), so a capture path that rediscovers the same work — a listener reconnect, a
manual force-check, a periodic sweep — re-sends the stored row and gets back its existing `requestId`
instead of minting a duplicate request:

```kotlin
// One-time setup: add the entity/DAO to your own @Database, then construct the queue once
// (e.g. in your DI container) — see §1's file pointers below for a concrete example.
@Database(entities = [/* your entities */, PendingLlmRequestEntity::class], version = N, ...)
abstract class YourDatabase : RoomDatabase() {
    abstract fun pendingLlmRequestDao(): PendingLlmRequestDao
}

val pendingLlmRequestQueue = VoxLlmRequestQueue(database.pendingLlmRequestDao())

// Sending (suspend — enqueueAndSend persists before it dispatches):
pendingLlmRequestQueue.enqueueAndSend(
    context = context,
    sourcePackage = context.packageName,
    task = LlmTasks.YOUR_TASK,
    promptText = yourPromptString,
    targetPackage = VoxAppsDiscovery.COMMANDER_PACKAGE,
    data = listOfNotNull(someContextString)
)
```

The reply lands in your `LlmResultReceiver` (§6.4's dispatch pattern) whenever VoxCommander's process
next gets to it; if your app was killed in between, the reply just arrives the next time your process
is running. Two additions to that receiver, both at the top of `onReceive` before your existing
per-task dispatch:

```kotlin
val (task, requestId) = VoxLlmRequestQueue.splitRequestId(result.task)   // task, not result.task, below
when (task) {
    LlmTasks.YOUR_TASK -> {
        // ... your existing parsing/dispatch, unchanged ...
        if (requestId != null) pendingLlmRequestQueue.markFulfilled(requestId)  // success OR error reply
    }
}
```

Finally, register the 15-minute retry worker (WorkManager's minimum periodic interval) in your
`Application.onCreate()` — implement `VoxLlmQueueHost` on your `Application` (one property,
`voxLlmRequestQueue`) and call `PendingLlmRequestScheduler.ensureScheduled(this)`; the scheduler and
worker live in `:core:ipc` (`PendingLlmRequestRetry.kt`) and reach your queue through the host
interface, so nothing is copied.

If you genuinely don't need durability for a specific one-off send (rare — most `ACTION_LLM_PROCESS`
traffic benefits from it), the raw pattern still works and is what the queue calls internally:

```kotlin
context.sendBroadcast(
    Intent(VoxIpc.ACTION_LLM_PROCESS)
        .setPackage(VoxAppsDiscovery.COMMANDER_PACKAGE)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)   // still worth setting even bypassing the queue
        .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, VoxLlmRequest(context.packageName, LlmTasks.YOUR_TASK, yourPromptString).toJson())
)
```

If you need to attach a photo (`attachmentUri`), check multimodal support first — don't assume it:

```kotlin
if (VoxCapabilityClient.isMultimodal(context)) {
    // safe to set attachmentUri on the VoxLlmRequest
}
```

`isMultimodal()` fails safe to `false` on timeout — treat it as an optimization check, not something
worth retrying.

The same query also reports whether the currently-selected engine runs on-device or calls out to a
cloud API — useful if your prompt has any scaffolding tuned for a small local model's weaknesses
(e.g. few-shot examples anchoring a case a bigger model would generalize to from prose alone) that a
capable remote model doesn't need and is better off without:

```kotlin
val local = VoxCapabilityClient.isLocalEngine(context) // fails safe to true — see below
val prompt = if (local) buildLocalTunedPrompt(...) else buildLeanPrompt(...)
```

Fails safe to `true` (not `false`) on timeout/unreachable — the opposite direction from
`isMultimodal()` — because an inconclusive probe should pick the more defensive, local-model-tuned
prompt rather than assume a capable remote model that may not actually be there. See
`NotificationExpenseParsePromptBuilder` in vox-expenses for a worked example of branching a prompt
this way.

---

## 8. Security model

### 8.1 Signature-level permissions are the entire trust mechanism

There is no API key, no token exchange, no server. `protectionLevel="signature"` means Android
itself refuses to deliver a permission-guarded broadcast unless the sender is signed with the
**identical certificate** as the receiver. This is why the shared keystore alias matters so much:

```kotlin
keyAlias = "vox-apps"   // must be this exact string, in every vox-* app's release signing config
```

A different alias — even one stored in the *same* keystore file — is a cryptographically unrelated
key. If your satellite's release build uses a different alias than VoxCommander's, the permission
checks silently fail: your receiver never gets called, `checkSignatures()` never matches, and (per
this repo's own experience) the *first* visible symptom can be as late as
`INSTALL_FAILED_DUPLICATE_PERMISSION` when installing release builds of two apps side by side —
long after the actual mismatch happened. Copy the signing block from an existing app's
`build.gradle.kts` verbatim; don't hand-type it.

Debug builds aren't subject to this concern the same way — AGP's default debug keystore is
typically shared across every module in one Android Studio checkout, so debug-to-debug testing
"just works" without the explicit signing config. Only release builds need `keyAlias = "vox-apps"`
to actually be exercised (CI sets `RELEASE_KEYSTORE_PATH`/`RELEASE_KEYSTORE_PASSWORD`; without them,
local `./gradlew assembleRelease` still produces a normal *unsigned* APK, same as always).

### 8.2 Defense in depth: `checkSignatures()` on top of the manifest permission

For **explicit-intent, non-permission-gated** deliveries (an async reply sent via
`sendBroadcast(Intent(...).setPackage(target))` rather than a permission-checked broadcast the OS
itself gates), VoxCommander independently re-verifies the sender/recipient's signature before
trusting/delivering:

```kotlin
val same = try {
    packageManager.checkSignatures(context.packageName, otherPackage) == PackageManager.SIGNATURE_MATCH
} catch (e: Exception) { false }
if (!same) { /* refuse */ return }
```

You'll see this exact pattern in VoxCommander's `LlmHookWorker.replyToSource()`,
`SatelliteHandler.deliverResult()`, and `SchemaChangedReceiver` (which also uses it to validate a
self-declared `EXTRA_SOURCE_PACKAGE`, since a plain broadcast doesn't otherwise expose caller
identity). If you build a receiver that accepts a self-declared sender identity in an extra, apply
the same check rather than trusting the extra at face value.

### 8.3 First-party vs third-party

`VoxAppsDiscovery.isFirstParty()` uses the same `checkSignatures()` check to decide whether a
discovered app is "first-party" (signed with VoxCommander's key) — this feeds `SatelliteRouting`'s
priority order when multiple apps could handle the same domain: an explicit user choice wins, then
the user's starred default, then first-party apps beat third-party ones, then a single remaining
candidate, then an arbitrary (logged, ambiguous) fallback.

---

## 9. Discovery & routing (how VoxCommander finds and picks you)

You don't need to implement anything for this section — it's entirely VoxCommander-side — but
understanding it helps when debugging "my app doesn't show up" or "the wrong app got picked":

- `VoxAppsDiscovery.discover(context)` calls
  `pm.queryBroadcastReceivers(Intent(VoxIpc.ACTION_COMMAND), PackageManager.GET_META_DATA)` — your
  receiver only shows up here if the `<intent-filter>` for `ACTION_COMMAND` and the `<meta-data>`
  tags are both present and correctly spelled. A typo in the action string or a missing `META_DOMAIN`
  tag means silent non-discovery, not an error.
- `VoxSatelliteRegistry` (VoxCommander-side) wraps discovery in a `StateFlow`, refreshed at
  VoxCommander startup and from the Integrations screen's Refresh button — plus a schema cache
  (DataStore-backed, survives process death) for `OP_GET_SCHEMA` results, keyed by package name.
- `SatelliteRouting.pick(candidates, starredPkg, explicitPkg)` is a pure function (no Android deps,
  fully unit-tested) implementing the priority order from §8.3.
- `SatelliteHandler` is the `IntentHandler` that actually dispatches a resolved voice command to
  your receiver (`OP_CREATE`/`OP_READ`) or, if your schema says `needsExtractionPass = true`, runs
  the collapsed-extraction path instead (§6.3).

---

## 10. Debugging checklist

- **App doesn't appear in Integrations after Refresh**: check your `<intent-filter>` action string
  is exactly `com.voxapps.action.VOX_COMMAND` and `META_DOMAIN`/`META_ACTIONS`/`META_LABEL` are all
  present with the exact key strings from §3 (typos fail silently — there's no discovery error to
  surface).
- **Receiver never fires**: confirm both apps are signed with `keyAlias = "vox-apps"` in release
  builds (§8.1), or are both plain debug builds sharing the default debug keystore.
- **`setResult()` throws `"Call while result is not pending"`**: you called `goAsync()` and are then
  calling the inherited `setResult()` instead of `pending.setResultData(...)` — see §2.3's `OP_READ`
  example.
- **Voice command creates the wrong thing / extraction fields are wrong**: if `needsExtractionPass =
  true`, remember your `OP_CREATE` handler is never invoked for voice-originated creates — the whole
  shape is decided by your `promptTemplate` + `LlmResultReceiver`'s parser, not your `VoxCommandReceiver`.
- **Schema seems stale after you changed your prompt/fields**: either bump `fieldSchemaVersion`
  (via the `@VoxExtractionSchema(version = N)` argument) as a visible marker, or push the change
  proactively with `VoxDataTransferClient.pushSchemaChanged(...)` (§6.5) instead of waiting for the
  next manual Refresh.
- **`nluHint` doesn't seem to affect NLU behavior**: it only takes effect after VoxCommander's
  registry has refreshed since your app was installed/updated — trigger Integrations → Refresh.

---

## 11. Reference: complete real examples in this repo

- **Simple path** (`needsExtractionPass = false`): `vox-notes/src/main/java/com/voxapps/notes/receiver/`
  — `VoxCommandReceiver.kt`, `LlmResultReceiver.kt` (category dedup + scan cleanup), `OcrResultReceiver.kt`.
- **Collapsed extraction** (`needsExtractionPass = true`): `vox-expenses/src/main/java/com/voxapps/expenses/receiver/VoxCommandReceiver.kt`
  and `vox-calendar/src/main/java/com/voxapps/calendarapp/receiver/VoxCommandReceiver.kt`, plus their
  respective `domain/llm/*ParsePromptBuilder.kt` / `*ParseResultParser.kt` pairs.
- **Contract types**: `core/ipc/src/main/java/com/voxapps/ipc/`.
- **Schema-versioning annotation + processor**: `core/schema-annotations/`, `core/schema-processor/`.
- **Data hygiene** (§6.6): `core/datahygiene/src/main/java/com/voxapps/datahygiene/` (`FieldCleaner.kt`,
  `JsonExtensions.kt`, `RecordSanitizer.kt`). Per-app `RecordSanitizer` implementations:
  `vox-expenses/.../data/ExpenseSanitizer.kt`, `vox-calendar/.../data/CalendarEntrySanitizer.kt`,
  `vox-notes/.../data/NoteSanitizer.kt` — and their wiring into each app's `LlmResultReceiver.kt` (LLM
  path) and edit screen (`ExpenseEditScreen.kt` / `EntryEditScreen.kt` / `NotesScreen.kt`, manual-UI path).
- **Duplicate-rule engine** (§6.7): `core/datahygiene/src/main/java/com/voxapps/datahygiene/RuleBasedDuplicateChecker.kt`.
  vox-expenses' concrete wiring: `data/ExpenseRuleFields.kt` (field registry), `data/DuplicateRuleEntity.kt`/
  `DuplicateRuleDao.kt` (Room storage, migrated + default-seeded), `ui/settings/DuplicateRulesSection.kt`
  (rule list/edit UI).
- **Merge-quality scoring** (§6.7): `core/datahygiene/src/main/java/com/voxapps/datahygiene/RecordScore.kt`
  (`RecordProvenance`, `recordScore()`). vox-expenses' concrete wiring: `data/ExpenseDataScore.kt`
  (`ExpenseSource`, `Expense.dataScore()`), consumed by `data/ExpenseNearDuplicateDetector.kt`
  (`enrichWithNearDuplicate`), `data/ExpensesRepository.kt` (`applyExpenseDeduplication`), and
  `ui/settings/ExpenseCleanupSettingsTab.kt` (keep-picker default selection + source tag).
- **VoxCommander's consuming side**: `vox-commander/src/main/java/com/voxapps/commander/domain/integration/`
  (`VoxSatelliteRegistry.kt`, `SatelliteRouting.kt`) and `.../domain/intent/handler/SatelliteHandler.kt`.
- **Durable LLM request queue** (§7): `core/ipc/src/main/java/com/voxapps/ipc/` (`PendingLlmRequestEntity.kt`,
  `PendingLlmRequestDao.kt`, `VoxLlmRequestQueue.kt`, `PendingLlmRequestRetry.kt` — the shared
  `VoxLlmQueueHost` interface, `PendingLlmRequestRetryWorker` and `PendingLlmRequestScheduler`). A
  `LlmResultReceiver.kt` in `vox-expenses`, `vox-notes`, or `vox-calendar` shows the
  `splitRequestId`/`markFulfilled` wiring on the receiving end.
- **Reusable category/tag color picker**: `core/design/src/main/java/com/voxapps/design/color/`
  (`VoxColorPalette.kt`, `VoxColorPicker.kt`) — see
  [`TECHNICAL_DOCUMENTATION.md` §20](TECHNICAL_DOCUMENTATION.md#20-shared-ui-modules-corecalendar-coreapppicker-coredesign-color-picker)
  for the design, and any of the three apps' category/layer add-edit dialogs for a consuming example
  (e.g. `vox-expenses/.../ui/settings/CategoriesSettingsTab.kt`).

---

## 12. Record creation: the shape every path ends in (`:core:recordflow`)

§6 and §7 are about *transport* — which mechanism carries a piece of text to a model and an answer
back. This section is about what a satellite does with what arrives, and the answer is the same
whether the text was spoken, photographed, or captured from a notification.

Three flows existed independently before this module, in three apps, and the disagreements between
them were never decisions anybody made: one created a record whenever it had a total, another queued
or created depending on a setting, a third had no offline path at all. `:core:recordflow` is that
shape written once.

### 12.1 The two halves, and why there are two

Asking a model crosses a process boundary and comes back later through a broadcast, so no single
call can carry a capture from input to record:

```kotlin
RecordFlow.dispatch(spec, input, level, send)   // read, decide, and either write or ask
RecordFlow.deliver(spec, reading, level, reply) // the answer arrived; finish the record
```

`dispatch` ends in one of four outcomes — `Committed(id)`, `Queued`, `Asked`, `Discarded` — and
`deliver` resumes when the reply lands. Papering over the seam with one suspending call would hide
the only part of the flow that can fail silently: the answer that never comes. That is also why
`send` is supplied by the caller rather than reached for inside the module — delivery is durable,
retryable, and belongs to the app that owns the queue (§7). `:core:recordflow` never composes a
prompt and never inspects one.

### 12.2 What a satellite implements

```kotlin
interface RecordFlowSpec<I, T, P> {
    val source: RecordSource                       // VOICE | SCAN | NOTIFICATION
    val support: FlowSupport                       // which rungs this flow can honour
    val taskId: String                             // the LlmTasks constant replies come back under

    suspend fun read(input: I): DeterministicReading<T>   // what the device can prove alone
    suspend fun prompt(reading: DeterministicReading<T>, asks: AskScope): String?
    suspend fun promptTemplate(asks: AskScope): String? = null   // for the transport that fills it
    suspend fun parse(reply: String): P?
    suspend fun commit(reading: DeterministicReading<T>?, parsed: P?,
                       applies: (FieldWeight) -> Boolean = { true }): Long?
    suspend fun queueForReview(reading: DeterministicReading<T>?, parsed: P?)
    fun autoAcceptWhenProven(): Boolean = true
}
```

`DeterministicReading(fields, usable, complete)` is the honest statement of what a rule settled:
`usable` means there is something worth keeping, `complete` means nothing needs asking. A page of
figures that reconciles is complete; a spoken sentence never is.

`reading` is nullable on `commit`/`queueForReview` because the bus carries the *answer* back, not the
question — see §6.4 for the two ways to recover the input when you need it.

### 12.3 The ladder: the model is a parameter, not a second flow

`LlmLevel` is eight rungs over two independent questions — *how much is sent* and *what fills itself
in* — rather than eight names to memorise:

| | asks nothing | asks what is missing | asks all the head fields | asks everything |
|---|---|---|---|---|
| **applies nothing** | `NONE` | `ASSIST_SUGGEST` | `HEAD_SUGGEST` | `ALL_SUGGEST` |
| **applies head fields** | — | `ASSIST_AUTO` | `HEAD_AUTO` | `BODY_SUGGEST` |
| **applies everything** | — | — | — | `FULL` |

`FieldWeight` splits a record into `HEAD` (the fields that identify it — amount, vendor, title) and
`BODY` (the fine detail — line items, per-hour figures), because those fail differently: a wrong
vendor is visible at a glance, while a list of plausible rows with invented amounts reads as data.
`AskScope` is what leaves the device; `applies(weight)` is what lands on the record without being
accepted first. A rung that asks but does not apply *offers* — which is what `:core:suggestions` is
for.

`RecordFlowPolicy.decide` switches on `level.asks` alone, so the offline behaviour is not a reduced
copy of anything: it is the same call with a level that asks nothing.

### 12.4 Declaring what you can honour

```kotlin
val SCAN_FLOW_SUPPORT = FlowSupport(
    source = RecordSource.SCAN,
    supported = LlmLevel.entries.toSet(),
    default = LlmLevel.FULL,
    suggestsAnswers = true,
    weights = setOf(FieldWeight.HEAD, FieldWeight.BODY)
)
```

Declare only what you can actually keep. A satellite with nowhere to hold a proposal cannot offer the
rungs that make one, and a flow with no fine detail declares `weights = setOf(FieldWeight.HEAD)` so
its settings card draws one checkbox rather than two. A stored level the flow cannot honour falls
back to its declared default and says so loudly in the log — that only happens when a rung is
withdrawn from under a saved setting, which somebody should see rather than have accommodated
quietly.

`ui/RecordFlowLevelCard` renders the two questions as checkboxes rather than eight compound labels,
and draws only what the passed `FlowSupport` admits.

### 12.5 A worked example

`vox-notes`' voice flow is the shortest honest implementation — a note's text *is* the record, so
nothing has to be extracted and `NotesSettings.VOICE_FLOW_SUPPORT` offers exactly one rung:

```kotlin
class NoteVoiceFlow(private val container: NotesContainer) : RecordFlowSpec<VoxCommand, SpokenNote, Unit> {
    override val source = RecordSource.VOICE
    override val support = NotesSettings.VOICE_FLOW_SUPPORT
    override val taskId = ""                       // nothing is ever asked

    override suspend fun read(input: VoxCommand) = DeterministicReading(
        fields = SpokenNote(input.title, input.text.orEmpty().trim(), input.category),
        usable = ..., complete = ...               // whole on arrival
    )
    override suspend fun prompt(reading: ..., asks: AskScope): String? = null
    override suspend fun parse(reply: String): Unit? = null
    override suspend fun commit(reading: ..., parsed: Unit?, applies: ...): Long? { ... }
    override suspend fun queueForReview(reading: ..., parsed: Unit?) = Unit
}
```

Written as a flow even though it never asks anything, because the shape is what is shared rather than
the work: a reader looking for where a spoken note becomes a note should find it in the same place as
its equivalents in the other apps.

### 12.6 Scanning: `:core:docread`

A scan's `read` step is where `:core:docread` belongs. It reads a document's rows and totals as
**combinations** rather than in sequence: a footer pattern proposes what the totals are, an items
pattern proposes what the rows are, and the pair is accepted only when the rows sum to one of those
totals to the cent. Neither half can be checked alone.

Two consequences worth knowing before you use it. Where no combination closes, the reading yields no
items at all — an empty list is a record a person completes, an invented one is a record they must
first notice is wrong. And the shapes themselves are data: `ReceiptTemplates` serves them from signed
schema, tried before the compiled-in battery, which follows rather than being replaced by them.

### 12.7 Reference

- **The module**: `core/recordflow/src/main/java/com/voxapps/recordflow/` — `RecordFlow.kt`,
  `RecordFlowSpec.kt`, `RecordFlowPolicy.kt`, `LlmLevel.kt`, `ui/RecordFlowLevelCard.kt`.
- **Proposals**: `core/suggestions/src/main/java/com/voxapps/suggestions/` — `SuggestionStore`,
  `FieldSuggestion`, `SuggestableField` (each app declares what of its own record may be suggested).
- **Document reading**: `core/docread/src/main/java/com/voxapps/docread/` — `ScanReading.of` is the
  entry point; `LineItemBattery`, `InvoiceTotalsReconciler`, `ReceiptTemplates` are the parts.
- **The seven implementations**: `NoteScanFlow`, `NoteVoiceFlow` (`vox-notes`), `CalendarScanFlow`,
  `CalendarVoiceFlow` (`vox-calendar`), `ExpenseScanFlow`, `ExpenseVoiceFlow`,
  `NotificationExpenseFlow` (`vox-expenses`) — all under each app's `domain/llm/`.
