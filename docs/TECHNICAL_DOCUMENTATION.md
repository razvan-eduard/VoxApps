# VoxCommander — Technical Documentation

> Part of the **VoxApps** monorepo (module `:vox-commander`, `com.voxapps.commander`, source under `vox-commander/`). Sibling apps (e.g. `:vox-notes`) are fully independent products — the only cross-app link is the optional **Vox contract** (`:core:ipc`, a contracts-only library): a satellite app self-registers voice capabilities and Commander routes commands to it over a local JSON bus. See [§19 Vox Apps Ecosystem](#19-vox-apps-ecosystem-cross-app-contract). This document covers `:vox-commander`.

## Table of Contents

1. [System Architecture](#1-system-architecture)
2. [Wake Word Detection](#2-wake-word-detection)
3. [Speech-to-Text (STT)](#3-speech-to-text-stt)
4. [Natural Language Understanding (NLU)](#4-natural-language-understanding-nlu)
5. [Intent Routing Pipeline](#5-intent-routing-pipeline)
6. [Intent Handlers](#6-intent-handlers)
7. [App Resolution & Aliases](#7-app-resolution--aliases)
8. [Media & Audio Integration](#8-media--audio-integration)
9. [Text-to-Speech (TTS)](#9-text-to-speech-tts)
10. [Search Providers](#10-search-providers)
11. [Settings & Preferences](#11-settings--preferences)
12. [UI Architecture](#12-ui-architecture)
13. [Model Management](#13-model-management)
14. [Memory Management](#14-memory-management)
15. [Return-to-Previous-App](#15-return-to-previous-app)
16. [External Voice Trigger](#16-external-voice-trigger)
17. [Dynamic JSON Configuration](#17-dynamic-json-configuration)
18. [Dependency Graph](#18-dependency-graph)
19. [Vox Apps Ecosystem (Cross-App Contract)](#19-vox-apps-ecosystem-cross-app-contract)
20. [Shared UI Modules (`:core:calendar`, `:core:apppicker`)](#20-shared-ui-modules-corecalendar-coreapppicker)

---

## 1. System Architecture

### Overview

VoxCommander is an on-device voice assistant for Android that follows a pipeline architecture:

```
Audio → Wake Word → STT → NLU → Intent Router → App Launch
```

### Process Model

- **`WakeWordService`** — Android foreground service (`FOREGROUND_SERVICE_TYPE_MICROPHONE`). Runs persistently, hosts the wake word engine and orchestrates the entire voice pipeline.
- **`VoxApplication`** — Application subclass. Handles memory pressure callbacks, releases heavy native models (Whisper, Vosk) on `TRIM_MEMORY` events.
- **`AppContainer`** — Dependency injection container. Initializes all services, loads settings, wires up the `IntentRouter` and `IntentDecisionMap`.

### Startup Flow

```
VoxApplication.onCreate()
  → AppContainer.init()
    → SettingsRepository (DataStore + EncryptedPrefs)
    → AppRegistry (PackageManager scan or cached JSON)
    → RemoteModelRegistry (parse models.json)
    → SearchProviderRegistry (parse search_definitions.json)
    → IntentCatalog (parse intents.json — before the AppRegistry probe scan)
    → ApiIntegrationRegistry (parse api_integrations.json — declarative per-service API defs)
    → SpotifyRemoteManager (set client ID; App Remote SDK, separate from the API integration below)
    → OAuth2Manager (init + load Spotify's persisted tokens)
    → NewPipeExtractorHelper.warmUp() (if newpipe engine selected)
    → PipedSearchHelper.useNewPipe flag set
    → reconcileDownloadedModels() (clear stale on-device flags vs disk)
    → WakeWordService started (if enabled)
```

### Key Classes

| Class | File | Role |
|-------|------|------|
| `AppContainer` | `di/AppContainer.kt` | DI container, startup wiring |
| `WakeWordService` | `service/WakeWordService.kt` | Foreground service, pipeline orchestration |
| `VoiceManager` | `domain/voice/VoiceManager.kt` | STT recording + Whisper inference |
| `IntentDecisionMap` | `domain/intent/IntentDecisionMap.kt` | Triple AI Brain orchestrator |
| `IntentRouter` | `domain/intent/router/IntentRouter.kt` | Intent dispatcher |
| `AppResolver` | `domain/intent/resolver/AppResolver.kt` | App resolution with aliases |
| `AppRegistry` | `domain/intent/registry/AppRegistry.kt` | Installed app catalog |
| `SettingsRepository` | `data/preferences/SettingsRepository.kt` | Settings interface |
| `SettingsRepositoryImpl` | `data/preferences/SettingsRepositoryImpl.kt` | DataStore + EncryptedPrefs impl |
| `AppStateManager` | `state/AppStateManager.kt` | Global state flow |

---

## 2. Wake Word Detection

### Engine Abstraction

All wake word engines implement `IWakeWordEngine`:

```kotlin
interface IWakeWordEngine {
    fun startDetection(callback: (String) -> Unit)
    fun stopDetection()
    fun isRunning(): Boolean
}
```

### Available Engines

All three wake engines are defined in `models.json` (`wake_vosk`, `wake_openwakeword`, `wake_porcupine`) and are selected by **capability**, not by hardcoded engine names. Relevant capabilities:

| Capability | Meaning |
|------------|---------|
| `wake_word_text` | User types the wake word (Vosk) |
| `calibration` | Supports a calibrated voice profile (Vosk) |
| `builtin_models` | The selected model IS the wake word — no free-text (OpenWakeWord, Porcupine) |
| `builtin_keywords` | Ships fixed built-in keywords (Porcupine) |
| `requires_api_key` | Blocks Start until a key is set (Porcupine → Picovoice AccessKey) |

`WakeWordService.startWakeWordDetection()` dispatches to the concrete engine class by engine key; everything else (which models exist, whether an API key is required, whether the model or a typed word is the trigger) is read from the schema via `RemoteModelRegistry.hasCapability(...)`.

#### Vosk (`WakeWordEngine.kt`)

- **Template Mode + Voice Print** — DTW (Dynamic Time Warping) matches the acoustic template of the user's wake word recording.
- **Voice Print Verification** — After template match, spectral features are compared against the user's voice print (threshold: 0.65 similarity). Rejects TTS audio and other speakers.
- **Calibration** — `WakeWordCalibrator` measures ambient noise floor and sets a calibrated detection threshold.
- **AEC** — Optional Acoustic Echo Cancellation for wake word detection during media/TTS playback (`wakeWordAecEnabled`).

#### Picovoice Porcupine (`PorcupineWakeWordEngine.kt`)

- Uses Picovoice's Porcupine SDK. The 13 built-in keywords (alexa, jarvis, computer, …) are defined as **non-remote models in `models.json`** (`wake_porcupine` engine) — no longer injected in Kotlin. Selecting one sets it as the wake word.
- Requires a Picovoice Access Key (`picovoiceAccessKey`); the Service tab disables **Start** and shows a warning until the key is entered (driven by the `requires_api_key` capability).
- Also supports custom `.ppn` model files in assets.

#### OpenWakeWord (`OpenWakeWordEngine.kt`)

- Fully open-source, ONNX-based wake word detection. Models are `wake_openwakeword` entries in `models.json` (bundled in `assets/openwakeword/`).
- **Startup warmup** — Detections in the first 1.0 s after each `start()` are ignored. Right after `start()` the mel/embedding feature buffers aren't primed and emit spurious high scores; without this guard the detect → command → re-arm cycle self-triggers into a loop.
- **Vendored + patched fork (`:core:wakeword`)** — no longer consumed as the `xyz.rementia:openwakeword`
  JitPack artifact. Upstream runs full ONNX inference (mel-spectrogram → embedding → classifier) on
  *every* ~80 ms audio buffer, including silence — the dominant CPU/battery cost of always-on wake word
  detection, and the library exposes no VAD/gating hook (`internal` visibility, no `feed()` API), so a
  wrapper couldn't fix it — only a source fork could. See [OpenWakeWord Fork & Sync](#openwakeword-fork--sync) below.

### Sensitivity (`WakeWordSensitivity.kt`)

The low/medium/high **Wake Word Sensitivity** setting maps to a per-engine threshold in one shared, unit-tested helper. Note the engines disagree on direction:

| Setting | OpenWakeWord (score ≥ threshold) | Porcupine (`setSensitivities`) | Vosk template DTW |
|---------|------|------|------|
| high | 0.25 (easier) | 0.7 (more sensitive) | 0.35 |
| medium | 0.5 | 0.5 | 0.45 |
| low | 0.7 (stricter) | 0.3 (less sensitive) | 0.55 |

Sensitivity is baked in at engine `initialize()`, so changing it requires an engine reload. The Service tab shows a **confirmation dialog** on change and, if the service is running, persists the value (awaiting the write and the reactive-state propagation) and then hot-reloads the engine so the new threshold applies immediately.

### Debounce & Notification

- **App-level debounce** — `WakeWordService.onWakeWordDetected()` drops triggers arriving within 2.5 s of the last accepted one (a full wake→command cycle always exceeds this).
- **Foreground notification** shows what actually triggers the wake, capability-driven: Vosk manual word → `Listening for '<word>'`; Vosk voice profile → `Listening <profileName>`; `builtin_models` engines → the selected model label (first ASCII-letter block, version suffix stripped, e.g. `hey_jarvis_v0.1.onnx` → `Hey Jarvis`).

### Wake Word Profile

`WakeWordProfile` stores:
- Template audio samples (for Vosk DTW)
- Voice print features (for speaker verification)
- Calibration data (noise floor, threshold)

Serialized as JSON in `wakeWordProfileJson` setting.

### OpenWakeWord Fork & Sync

**Why forked:** upstream (`Re-MENTIA/openwakeword-android-kt`) runs its full ONNX pipeline on every
buffer regardless of silence, with no exposed gating hook — a real, measurable battery cost for an
always-on service. Vosk already had bandpass+RMS VAD gating; Porcupine is closed-source and
purpose-built as a lightweight DSP algorithm (not prioritized — small expected gain, unverifiable
internals). OpenWakeWord was the one engine that actually needed the fix, and fixing it required
owning the source.

**Structure:**

| Path | Role |
|------|------|
| `vendor/openwakeword-android-kt` | Git submodule — pristine upstream source at a pinned tag. Reference only, never compiled directly. |
| `core/wakeword/` | Local Gradle module (`android-library`) — vendored + patched copy of the upstream `:wakeword` module, compiled into `vox-commander`. |
| `core/wakeword/src/main/kotlin/.../audio/AudioRecorder.kt` | The one patched file. An RMS silence gate drops buffers below an energy floor *before* the short→float conversion and *before* anything is emitted — so `WakeWordEngine`'s ONNX inference never runs on silence. Gate floor (`rmsGate`) is derived from the user's Wake Word Sensitivity setting via `WakeWordSensitivity.openWakeWordRmsGate()`; `0f` preserves upstream behavior. |
| `core/wakeword/patches/0001-rms-silence-gate.patch` | The patch, maintained as a real unified diff (not just "the current file") — regenerate with `scripts/regen_openwakeword_patch.sh` any time the patch itself changes. |
| `core/wakeword/NOTICE` / `LICENSE` | Apache 2.0 attribution chain (OpenWakeWord, Google Speech Embedding Model, ONNX Runtime). |

**Keeping it in sync with upstream releases:**

- `scripts/check_openwakeword_version.sh` — local, non-destructive dry-run: checks for a newer upstream
  tag and whether the stored patch would still `git apply --check` cleanly against it, without touching
  the working tree either way. Wired into `vox-commander`'s `preBuild` as the `autoCheckOpenWakeWord`
  Gradle task.
- `.github/workflows/sync-openwakeword.yml` — weekly scheduled (+ manual dispatch) workflow: on a new
  upstream tag, bumps the submodule, fully re-vendors `core/wakeword`'s sources, and tries to `git apply`
  the stored patch. If it applies cleanly *and* the module compiles + unit tests pass, it opens a PR
  that's already ready to review/approve — nothing to hand-merge in the common case. It only surfaces a
  manual-merge PR (with the reject hunk attached) if the patch genuinely conflicts with an upstream
  change to the same lines. Never auto-merges.
- The same pattern (submodule + scheduled sync workflow, PR-per-update, never auto-merged) is used for
  Whisper.cpp (`sync-whisper.yml`, monthly — compiles/tests only, deliberately never publishes the
  production `.so` DLC) and Vosk (`sync-vosk.yml`, weekly — Vosk is a binary Maven/JitPack dependency,
  not vendored source, so it's a version bump in `gradle/libs.versions.toml` rather than a patch).

---

## 3. Speech-to-Text (STT)

### Whisper.cpp Integration

- **Native library**: `libwhisper.so` (GGML-based, compiled via CMake)
- **Engine**: `WhisperSttEngine` in `domain/engine/whisper/`
- **Models**: Downloaded on-demand from HuggingFace (`ggml-tiny.bin`, `ggml-base.bin`, `ggml-small.bin`)
- **Release builds**: Whisper native libs excluded from APK via AGP's
  `packaging.jniLibs.excludes` (reliable for this lib), downloaded as real, user-facing DLC — the
  model download above is the user-visible part of the same mechanism. onnxruntime, Vosk,
  mediapipe-genai, and sherpa-onnx-jni are stripped from the APK the same way but aren't DLC in that
  sense: there's no user choice involved, they're mandatory libraries the app needs to function at
  all, silently fetched once on first launch — stripped via a post-build script instead of AGP's
  exclude mechanism, which is confirmed-unreliable on arm64-v8a for those specific libs (see
  `docs/BUILD_TIME_DEPENDENCIES.md`). CI-published release: ~166MB → ~16MB. A plain local
  `assembleRelease` only gets the Whisper reduction (~40MB) since the strip step lives in
  `release-commander.yml`, not Gradle.
- **Vulkan**: Optional GPU acceleration via `libggml-vulkan.so` (probed at first run, disabled if incompatible)

### STT Flow

```
VoiceManager.startListening()
  → AudioRecord (16kHz, 16-bit PCM, mono)
  → Record until silence detected (configurable timeout)
  → WhisperSttEngine.transcribe(audioBytes)
    → JNI call to whisper.cpp
    → Returns text transcript
  → Text passed to IntentDecisionMap
```

### Sensitivity

`sttSensitivity` setting controls the silence detection threshold:
- **low** — Requires louder audio to start, stops quickly on silence
- **medium** — Balanced
- **high** — Sensitive to quiet speech, longer silence timeout

### Multilingual Support

Whisper models are multilingual. `voiceLanguage` and `voiceLanguageAutoDetect` settings control:
- **Auto-detect** — Whisper detects language from first 30 seconds
- **Fixed language** — Forces Whisper to decode in specified language

---

## 4. Natural Language Understanding (NLU)

### Triple AI Brain (`IntentDecisionMap`)

The NLU pipeline has three levels with fallback:

```
L1: FastMap (regex rules) — instant, no ML
  ↓ miss
L2: Primary selected engine — user's chosen AI
  ↓ miss/failure
L3: Offline fallback — user's configured fallback model
```

#### L1: FastMap (`FastMapEngine.kt`)

- Regex-based pattern matching against user-defined rules
- Rules stored in Room database (`FastIntentEntity`)
- Each rule maps a regex pattern to a `NluIntent` directly
- Confidence: always 1.0 (exact match)
- Editable via UI (Settings → Rules)

#### L2: Primary Engine

User selects via `aiProcessor` setting:

| Processor | Engine | Description |
|-----------|--------|-------------|
| `openai` | `OpenAiInterpreter` | OpenAI Chat Completions API (cloud) |
| `gemini_native` | `GeminiNanoInterpreter` | Gemini Nano on-device (AICore) |
| `gemini_cloud` | `GeminiCloudInterpreter` | Gemini Pro API (cloud) |
| Custom LLM | `LocalLlmInterpreter` | llama.cpp via MediaPipe GenAI |

All interpreters implement `AssistantEngine`:

```kotlin
interface AssistantEngine {
    suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent?
}
```

#### L3: Offline Fallback

If L2 fails (no internet, model not loaded, API error), L3 tries the user's configured fallback model (`defaultIntentFallbackProcessor` + `defaultIntentFallbackModel`). Skips if same as L2.

### NluIntent Data Model

```kotlin
data class NluIntent(
    val actionVerb: String,           // "play", "caută", "spune" (original language)
    val logicalSubject: String?,      // "Scorpions", "România" (the entity)
    val modifiers: List<String>,      // ["rapid"], ["încet"] (how)
    val contextWords: List<String>,   // ["pe", "spotify"] (where/app)
    val domain: String,               // "audio", "maps", "messaging", etc.
    val action: String,               // "play", "navigate", "send", etc.
    val targetApp: String?,           // "spotify", "youtube" (user-specified)
    val category: String?,            // search category: "general", "news", "weather"
    val confidence: Float,            // 0.0–1.0
    val extras: Map<String, String>,  // additional params (e.g. message_body)
    val intentAction: String?,        // Android intent action (FastMap)
    val uriTemplate: String?,         // URI template (FastMap or probe)
    val mediaControlType: String?,    // "active_session", "default_app", "audio_button"
    val mediaType: String?            // "track" (default)/"album"/"artist" — audio+play only, LLM-set
)
```

### Intent Taxonomy (`IntentTaxonomy`)

Single source of truth for domains and actions:

| Domain | Actions |
|--------|---------|
| `audio` | play, pause, stop, next, prev |
| `settings` | volume_up, volume_down, wifi_toggle, bluetooth_toggle, gps_toggle |
| `maps` | navigate |
| `messaging` | send |
| `search` | query |
| `system` | toggle, status |
| `home` | toggle, status |

### Prompt System (`PromptProvider.kt`)

The NLU prompt is defined in `models.json` under `prompts.standard_nlu`. It instructs the LLM to:
1. Dissect the sentence into grammatical roles (action_verb, logical_subject, modifiers, context_words)
2. Classify into domain + action from the taxonomy
3. Extract targetApp if explicitly mentioned
4. Return JSON matching `NluIntent` fields

The prompt is multilingual — it works in any language and always returns domain/action in English.

**`stripToRules()` and rule placement — a load-bearing detail.** `getNluSystemPrompt()` sends the LLM
only the *rules* portion of the template, not the few-shot examples: `stripToRules()` cuts the raw
`standard_nlu` string at the **first occurrence of `"Examples:"`** and discards everything after it
(the examples are for readability/authoring, not for the LLM — the numbered rules are meant to be
self-describing). This means **any rule — including `SATELLITE OVERRIDE` — must be written before the
`Examples:` marker in `models.json`, or the LLM silently never sees it.** This exact mistake shipped
once (the override was appended at the end of the file, after `Examples:`, so every satellite `create`
command silently lost its category/content extraction until the placement was fixed) — see the
regression tests in `PromptProviderTest.kt` (`a rule appended after Examples never reaches the model`,
`the real models json prompt keeps SATELLITE OVERRIDE before the Examples cut`), which read the actual
repo-root `models.json` and assert the rule survives the cut.

**Shared rule vs. per-satellite hint.** `SATELLITE OVERRIDE` (in `models.json`) is intentionally
domain-agnostic — the universal create/read stripping semantics for *any* companion-app domain — and
does not need to grow as more satellites are added. Anything domain-*specific* (e.g. Vox Notes' spoken
`category`) instead belongs in that satellite's own `nluHint` manifest declaration, surfaced via
`buildSatelliteHints()` — see [§19 Domain-specific NLU hints](#domain-specific-nlu-hints-nluhint).

### NluIntentParser (`NluIntentParser.kt`)

Post-processes LLM output:
- Validates domain and action against `IntentTaxonomy`
- Normalizes target app names
- Infers missing fields from anatomy (e.g., if action_verb is "caută" → domain=search)
- Handles edge cases (generic subjects, multi-word proper nouns)

---

## 5. Intent Routing Pipeline

### IntentRouter (`IntentRouter.kt`)

Central dispatcher that:

1. Gets `AppSettings` snapshot
2. Calls `AppResolver.resolve(intent, settings)` to get the target `AppEntry`
3. Iterates registered `IntentHandler` implementations
4. First handler where `canHandle(intent) == true` executes the intent
5. If `returnAfterActionApps` contains the resolved package, saves foreground app and relaunches it after 1.5s

### Handler Registration Order

```kotlin
val handlers = listOf(
    SatelliteHandler(),                       // domain=<any advertised by a Vox satellite> — see §19
    SearchIntentHandler(settingsRepository),  // domain=search
    AudioIntentHandler(),                     // domain=audio
    NavigationIntentHandler(),                // domain=maps
    SystemIntentHandler(),                    // domain=settings, system, home
    MessagingIntentHandler(),                 // domain=messaging
    GenericLaunchHandler()                    // fallback: launch app by name
)
```

`SatelliteHandler` is first so that domains contributed by companion apps (e.g. `notes`) are routed
over the Vox contract before the built-in handlers. It only `canHandle()` a domain that a discovered
satellite advertises, so it's inert when no satellite is installed.

### IntentHandler Interface

```kotlin
interface IntentHandler {
    fun canHandle(intent: NluIntent): Boolean
    fun execute(context: Context, intent: NluIntent, resolvedApp: AppEntry?): Boolean
}
```

---

## 6. Intent Handlers

### AudioIntentHandler (`AudioIntentHandler.kt`)

Handles `domain=audio` with actions: play, pause, stop, next, prev.

**Play search fallback chain:**

1. **Declarative API integration** (`AudioPlaybackHelpers.tryApiIntegrationPlaySearch`) — If the resolved
   app has a loaded, authorized `ApiIntegration` (§8) declaring a `play_${mediaType ?: "track"}`
   capability, search + play via that service's own API (no UI), falling back to `play_track` if the
   service has no dedicated slot for the requested media type. Spotify ships as the first integration
   today.
2. **MEDIA_PLAY_FROM_SEARCH** — Android standard media search intent
3. **YouTube search** (NewPipe or Piped) — Resolves query to videoId, launches `youtu.be/{id}` in target app
4. **URI template** — Uses app's `uriTemplate` with query substitution
5. **Launch app directly** — Fallback: just open the app

**Media controls** (pause, stop, next, prev):
- Sends media key events via `MediaSessionManager`
- Targets active media session or explicitly named app

### NavigationIntentHandler (`NavigationIntentHandler.kt`)

Handles `domain=maps` with action: navigate.

- Constructs `geo:` URI with destination
- Launches Waze (`waze://`) or Google Maps (`google.navigation:`) deep link
- Falls back to `geo:0,0?q={destination}` implicit intent

### SystemIntentHandler (`SystemIntentHandler.kt`)

Handles `domain=settings`, `domain=system`, `domain=home`.

- **Volume** — `AudioManager.adjustVolume()` with `STREAM_MUSIC`
- **WiFi** — `WifiManager.setWifiEnabled()` (or Settings panel on Android 10+)
- **Bluetooth** — `BluetoothAdapter.enable()` / `disable()`
- **GPS** — Launches location settings (can't toggle directly on modern Android)
- **Home** — `ACTION_HOME` or smart home device toggles

### MessagingIntentHandler (`MessagingIntentHandler.kt`)

Handles `domain=messaging` with action: send.

- Constructs `ACTION_SEND` intent with `EXTRA_TEXT` = message body
- Targets WhatsApp, Telegram, SMS, etc. by package name
- Falls back to chooser dialog

### SearchIntentHandler (`SearchIntentHandler.kt`)

Handles `domain=search` with action: query.

- Routes to `SearchProviderRegistry` based on `intent.category`
- Categories: general, news, knowledge, weather
- Opens results in browser or dedicated app

### GenericLaunchHandler (`GenericLaunchHandler.kt`)

Fallback handler — launches any app by name or package.

- Resolves app name via `AppRegistry.resolveByName()`
- Launches with `packageManager.getLaunchIntentForPackage()`
- Supports URI templates for deep linking

---

## 7. App Resolution & Aliases

### AppRegistry (`AppRegistry.kt`)

- **Dynamic scan** — `PackageManager` scan at startup, caches results as JSON (`appCacheJson`)
- **Capability probing** — For each installed app, `KnownIntents.probeSupported()` fires each catalog entry against `queryIntentActivities` (by probe URI / MIME type / bare action) to discover which intents that app actually supports, plus a "launch" fallback.
- **Domain mapping** — Deduced from each supported entry's `templateAction` via `template_action_domains` (navigate→maps, search→audio, send→messaging).
- **URI templates** — Taken from the matched catalog entries (e.g., a browser `VIEW`/`https` → `https://www.youtube.com/watch?v={query}`).

**The probe catalog is data-driven** — see `IntentCatalog` (`domain/intent/registry/IntentCatalog.kt`), which loads `intents.json` (root → assets → filesDir → remote, hot-reloadable like `models.json`; see §17). A compact hardcoded seed is used only if the asset read fails. The behavioral handlers (§6) stay in code; the catalog only feeds them.

`AppSelectorDropdown.kt` (the domain-app picker UI used by the Default Apps / App Manager / Rules Manager screens) is now a thin wrapper around the shared `:core:apppicker` card component — it keeps Commander-specific concerns (satellite/domain-aware candidate filtering, Spotify OAuth interception) and delegates all rendering (search, all/user/system filter, checkbox+star list) to the shared module. See [§20 Shared UI Modules](#20-shared-ui-modules-corecalendar-coreapppicker).

### AppResolver (`AppResolver.kt`)

Resolution order:

1. **Alias rules** — Check `appAliasRules` for user-defined name mappings (e.g., "youtube" → `com.github.libretube`)
2. **Explicit targetApp** — If user said "on Spotify", resolve to `com.spotify.music`
3. **Domain default** — Use `defaultAppPackages[domain]` (user's preferred default per domain)
4. **Domain apps** — Pick first from `domainAppPackages[domain]`
5. **System default** — Implicit intent (let Android choose)

### App Aliases

Users can define alias rules:

```kotlin
data class AppAliasRule(
    val id: String,
    val displayName: String,
    val packageName: String,
    val aliases: List<String>,   // e.g., ["youtube", "yt"]
    val enabled: Boolean
)
```

When user says "play Scorpions on YouTube", and alias rule maps "youtube" → `com.github.libretube`, the resolver returns LibreTube's `AppEntry`. The handler then uses LibreTube's package name for the intent.

---

## 8. Media & Audio Integration

### Declarative API Integration Engine

Controlling an external app's own service API (search a catalog, start playback via that service's
*backend*, not just a local intent) is defined entirely in `api_integrations.json` (repo root, copied
into assets — same convention as `intents.json`/`models.json`) and executed by two generic engines.
Adding a new OAuth-based service (Deezer, Tidal, ...) needs **zero new Kotlin** — just a new entry in
the JSON, as long as it fits the capability-slot model below.

#### Capability slots (`ApiIntegrationRegistry.kt`)

Every service definition fills in only the slots it actually supports out of a fixed set:
`search_track`, `search_album`, `search_artist`, `play_track`, `play_album`, `pause`, `next`, `prev`,
`stop`. An **absent** slot isn't an error — it means "no override, fall back to the existing generic
mechanism" (the `AudioIntentHandler` fallback chain in §6, or `MediaSessionListenerService` for
transport controls). This mirrors `ITtsEngine`'s "fixed interface, silent no-op for unsupported parts"
pattern already used elsewhere in this codebase.

A slot's value is one of:
- `api_call` — one authenticated HTTP request (`method`/`path`/`body`/`response_path`, plus an
  optional `extract` map for pulling extra named values out of the same response — e.g. `search_track`
  also extracts the track's `artist_id` alongside its primary URI result, without a second HTTP call).
- `api_sequence` — an ordered list of steps for slots needing more than one request (Spotify's
  `play_track`: search → list devices → pick one → transfer → play → queue a few more of the artist's
  top tracks). Step modifiers (`optional`, `group`, `retry`, `delay_before_ms`) let a purely
  declarative sequence reproduce real retry/fallback behavior — e.g. Spotify's device-transfer call is
  fire-and-forget (`optional: true`); the device-targeted play call and the device-less fallback play
  call share `group: "play"` so the first to succeed satisfies the whole group and the sequence
  continues on to the queueing steps afterward, rather than the whole sequence returning early.
- `device_select` (step type only) — generic version of the old device-preference heuristic:
  `prefer: [{field, equals}, ...]` predicates tried in order, first match wins, else first item.
- `queue_array` (step type only) — best-effort, never fails the sequence: queues up to `limit` items
  from a prior step's stored array (skipping one matching value, e.g. the track just played) via
  `queue_path`. Used by `play_track` to queue a few of the played track's artist's other top tracks —
  Spotify's real Recommendations endpoint was deprecated in November 2024 and is unavailable to new
  apps, so top-tracks is the honest, still-live substitute.
- `deep_link` — a URI template (reuses `intents.json`'s substitution mechanism), no auth. This is how
  a service with no remote-play API (e.g. Deezer) would express playback: resolve an ID via a real API
  search call, then hand off to the installed app via deep link.

```json
"play_track": {
  "type": "api_sequence",
  "steps": [
    { "capability": "search_track" },
    { "type": "api_call", "method": "GET", "path": "/me/player/devices", "response_path": "devices", "as": "devices", "optional": true },
    { "type": "device_select", "from": "devices", "prefer": [{"field": "type", "equals": "Smartphone"}], "id_field": "id", "as": "device_id", "optional": true },
    { "type": "api_call", "method": "PUT", "path": "/me/player", "body": "{\"device_ids\":[\"{device_id}\"],\"play\":false}", "optional": true },
    { "type": "api_call", "group": "play", "method": "PUT", "path": "/me/player/play?device_id={device_id}", "body": "{\"uris\":[\"{search_track.result}\"]}", "delay_before_ms": 500, "retry": {"times": 1, "delay_ms": 2000} },
    { "type": "api_call", "group": "play", "method": "PUT", "path": "/me/player/play", "body": "{\"uris\":[\"{search_track.result}\"]}" },
    { "type": "api_call", "method": "GET", "path": "/artists/{search_track.artist_id}/top-tracks", "response_path": "tracks", "as": "top_tracks", "optional": true },
    { "type": "queue_array", "from": "top_tracks", "uri_field": "uri", "limit": 4, "skip": "{search_track.result}", "queue_path": "/me/player/queue?uri={item}", "method": "POST", "optional": true }
  ]
}
```

Placeholders (`{query}`, `{token}`, `{device_id}`, `{search_track.result}`) are substituted before each
request — URL-encoded in `path`, JSON-escaped in `body`. Response values are extracted via a small
dot/bracket JSON-path walker (`tracks.items[0].uri`, `devices`).

#### `DeclarativeApiExecutor.kt`

Runs one named capability: dispatches `api_call`/`api_sequence`/`deep_link`, walks JSON response paths,
and reuses plain `HttpURLConnection` helpers (no external HTTP library). `search_album`/`search_artist`/
`play_album`/`play_artist` are defined for Spotify (`play_album`/`play_artist` use `context_uri` instead
of `uris`, per Spotify's real API) and **are reachable from voice**: `NluIntent.mediaType` (§4) carries
the LLM-detected "track"/"album"/"artist" signal, and `AudioPlaybackHelpers.tryApiIntegrationPlaySearch`
dispatches to the matching `play_${mediaType}` capability (falling back to `play_track` if a service has
no dedicated slot for the requested type) — so "play the album Nevermind" correctly resolves to
`play_album`, not `play_track`.

#### `OAuth2Manager.kt`

Generic OAuth2 Authorization-Code(+PKCE) client, parameterized per service (`auth.type` in JSON:
`oauth2_pkce` — no client secret, e.g. Spotify; `oauth2_authorization_code` — wants a client secret,
e.g. Deezer's confidential-client flow). Tokens are stored in `EncryptedSharedPreferences`, keyed by
service id (`"${serviceId}_access_token"` etc — Spotify's pre-existing `spotify_access_token` keys
already matched this format, so no migration was needed).

**Shared OAuth redirect URI**: every service's `redirect_uri` can be the same literal string
(`voxcommander://oauth/callback`, one manifest `<intent-filter>`, registered once ever) because service
identity travels through the standard OAuth2 `state` parameter (`state=<serviceId>:<nonce>`, the nonce
doubling as CSRF protection) instead of the URI itself. `MainActivity.handleOAuthRedirect()` resolves
the target service from `state` regardless of which host actually caught the redirect — Spotify keeps
using its own pre-existing `voxcommander://spotify/callback` manifest entry (unchanged, so no existing
Spotify Developer Dashboard config needs updating), but still round-trips through the same `state`
mechanism, proving it end-to-end without requiring a second service to exist yet.

#### Spotify (`api_integrations.json`, id `"spotify"`)

The first — and so far only — integration. Search/play migrated fully onto the generic engine described
above (`AudioPlaybackHelpers.tryApiIntegrationPlaySearch()`); `SpotifyPkceManager.kt`/`SpotifyWebApi.kt`
were deleted once the migration was verified against real Spotify playback (search → device discovery →
transfer → play, matching the original hand-written retry sequence). Client ID storage is not yet
generalized — `AudioPlaybackHelpers.clientIdFor()` still reads Spotify's client id via
`SpotifyRemoteManager.getClientId()` specifically; generalizing that (and adding a second service) is
deferred, real follow-on work.

#### Spotify App Remote SDK (`SpotifyRemoteManager.kt`)

Separate from the API integration above — a persistent, stateful SDK connection with push-based
callbacks, which can't be expressed as a declarative REST endpoint regardless of schema design. Out of
scope for the declarative engine; used independently for `AppSelectorDropdown`'s OAuth-nudge dialog and
kept exactly as it was.

- Uses Spotify's App Remote SDK (`spotify-app-remote.aar`)
- Connection requires Spotify app installed on device
- `connect()` — blocking, 30s timeout (internal use)
- `connectAsync()` — non-blocking, 60s timeout (UI use, for OAuth)
- Once connected: `playSearch()`, `playUri()`, `resume()`, `pause()`, `skipNext()`, `skipPrevious()`

### YouTube / LibreTube

#### Piped API (`PipedSearchHelper.kt`)

- Cloud-based YouTube search via Piped instances
- Multiple instances with fallback (`PIPED_INSTANCES` list)
- User selects instance + region in settings
- `searchAndPlay()` — searches Piped, gets videoId, launches `youtu.be/{id}` in target app

#### NewPipe Extractor (`NewPipeExtractorHelper.kt`)

- On-device YouTube parsing (no external API)
- Uses `com.github.teamnewpipe:NewPipeExtractor:v0.26.3`
- `OkHttpDownloader` — custom `Downloader` implementation using OkHttp
- `warmUp()` — pre-fetches `base.js` (YouTube's player JavaScript) to cache Rhino JS engine output. First query is slow (5-10s), subsequent queries are fast.
- `searchAndPlay()` — searches YouTube, gets first video result, launches `youtu.be/{id}`
- `testConnection()` — runs a test search, validates results contain video URLs

#### Engine Selection

User selects via `youtubeUrlEngine` setting:
- `"piped"` — Use Piped API (cloud)
- `"newpipe"` — Use NewPipe Extractor (on-device)

`PipedSearchHelper.useNewPipe` — static volatile flag checked by `AudioIntentHandler` to decide which engine to use. Set at startup in `AppContainer` and on selection change in `PipedSettingsSection`.

### Media Session Control (`MediaSessionListenerService.kt`)

- Uses `MediaSessionManager` to monitor active media sessions
- Requires `android.permission.MEDIA_CONTENT_CONTROL` or notification listener permission
- Enables pause/resume/next/prev on any active media app

---

## 9. Text-to-Speech (TTS)

### Engine Abstraction (`ITtsEngine.kt`)

```kotlin
interface ITtsEngine {
    fun speak(text: String, language: String)
    fun stop()
    fun isSpeaking(): Boolean
}
```

### Android TTS (`AndroidTtsEngine.kt`)

- Uses Android's `TextToSpeech` engine
- Supports speech rate and pitch adjustment
- Language set per utterance

### Piper TTS (`PiperTtsEngine.kt`)

- On-device neural TTS via `sherpa-onnx`
- Voice models downloaded on-demand (`.onnx` + `.tokens` files)
- Higher quality than Android TTS
- Models stored in app files directory
- **Voice selection (`piperVoiceModelId`)** — the user's explicit pick from the Piper voice picker in
  Settings, persisted like the STT/wake-word active-model settings. `TtsManager` tracks which voice id
  the live engine instance was actually built with and rebuilds it when the setting changes;
  `PiperTtsEngine.preferredVoiceId` (set by `TtsManager` before `initialize()`) wins over the engine's
  own language-only fallback heuristic (which prefers a "low" quality voice for the current language if
  nothing was ever explicitly selected).

### Audio Focus (`ttsAudioFocusMode`)

- **none** — TTS plays over media
- **duck** — Lowers media volume during TTS (default)
- **pause** — Pauses media during TTS, resumes after

### TtsManager (`TtsManager.kt`)

- Manages TTS engine lifecycle
- Handles language switching
- Queues utterances
- Integrates with `VoiceManager` for response playback

---

## 10. Search Providers

### SearchProviderRegistry (`domain/search/SearchProviderRegistry.kt`)

- Parses `search_definitions.json` (shipped in assets, updated from remote GitHub)
- Categories: `general`, `news`, `knowledge`, `weather`
- Each provider has: name, API endpoint, API key requirement, response parser

### Providers

| Category | Providers |
|----------|-----------|
| General | DuckDuckGo (Instant Answer), Wikipedia |
| News | Google News, GNews, Currents API, NewsAPI |
| Knowledge | Wikipedia |
| Weather | WeatherAPI, Open-Meteo |

API keys stored encrypted in `EncryptedSharedPreferences` (`searchProviderApiKeys` map).

---

## 11. Settings & Preferences

### DataStore + EncryptedPrefs (`SettingsRepositoryImpl.kt`)

- **Jetpack DataStore** — Most settings (preferences, JSON-serialized maps/lists)
- **EncryptedSharedPreferences** — Sensitive data (API keys, Spotify tokens)
- **Room** — FastMap rules (`FastIntentEntity`)

### AppSettings (`AppSettings.kt`)

Immutable data class containing all persisted settings. Emitted as a `Flow` via `settingsFlow`. Key groups:

| Group | Fields |
|-------|--------|
| API/Cloud | `apiKey`, `geminiApiKey` |
| Language | `language`, `voiceLanguage`, `voiceLanguageAutoDetect`, `modelFilterLang` |
| Voice Engine | `voiceProcessor`, `activeVoiceModelId` |
| Intent Engine | `aiProcessor`, `activeIntentModelId`, `cloudIntelligenceEnabled` |
| Wake Word | `wakeWord`, `wakeWordEnabled`, `wakeWordModelPath`, `wakeWordEngineType`, `wakeWordSensitivity`, `wakeWordAecEnabled` |
| Offline Fallback | `offlineFallbackTimeout`, `defaultOfflineModel`, fallback processors/models |
| Default Apps | `defaultAppPackages`, `domainAppPackages`, `customDomains`, `domainAppFilters` |
| Media | `spotifyClientId`, `pipedApiUrl`, `pipedRegion`, `youtubeUrlEngine`, `returnAfterActionApps` |
| TTS | `ttsEnabled`, `ttsEngineType`, `ttsSpeechRate`, `ttsPitch`, `ttsAudioFocusMode`, `piperVoiceModelId` |
| Aliases | `appAliasRules` |
| Location | `manualLocationLat`, `manualLocationLon` |
| Vulkan | `vulkanIncompatible`, `vulkanProbeDone`, `experimentalVulkanEnabled` |
| Logging | `logLevel`, `verboseLoggingEnabled` |

### Sync vs Async

- **Sync getters** (`getXxxSync()`) — Use `runBlocking { dataStore.data.first() }`. Called from non-suspend contexts (UI, service).
- **Async setters** (`suspend fun setXxx()`) — Use `dataStore.edit { }`. Called from coroutines.

---

## 12. UI Architecture

### Compose + Material 3

- Single-activity architecture (`MainActivity`)
- `SettingsContent` — Tabbed settings with 7 tabs
- `ListeningScreen` — Main voice interaction screen with overlay
- Navigation via Compose Navigation

### Settings Tabs

| Tab | File | Content |
|-----|------|---------|
| General | `GeneralSettingsTab.kt` | Language, wake word toggle, TTS settings |
| App Manager | `AppManagerTab.kt` | Default apps per domain, media session permission, return-to-previous-app |
| Services | `ServiceSettingsTab.kt` | Voice engines, intent engines, wake word config, Piped/NewPipe settings |
| Integrations | `IntegrationsTab.kt` | Spotify OAuth, search providers, API keys |
| Models | `ModelsSettingsTab.kt` | Model downloads, Whisper/Piper/Vosk model management |
| Advanced | `AdvancedSettingsTab.kt` | Vulkan, logging, offline fallback, diagnostics |
| Permissions | `PermissionsSettingsTab.kt` | Runtime permissions management |

### Reusable Components

| Component | File | Usage |
|-----------|------|-------|
| `AppSelectorDropdown` | `ui/components/AppSelectorDropdown.kt` | Single + multi-select app picker with search |
| `ConnectionTestIndicator` | `ui/components/ConnectionTestIndicator.kt` | ✅/❌/spinner status for API tests |
| `ConnectionTestAuto` | `ui/components/ConnectionTestIndicator.kt` | Auto-testing variant with `LaunchedEffect` |

### State Management

- `AppStateManager` — Holds `StateFlow<AppState>` combining settings + runtime state
- `collectAsStateWithLifecycle()` — Compose state collection tied to lifecycle
- `ServiceLoadingState` — Tracks wake word engine loading progress

---

## 13. Model Management

### RemoteModelRegistry (`data/remote/RemoteModelRegistry.kt`)

- Parses `models.json` (shipped in assets, updated from remote GitHub raw URL)
- Defines available engines and their models:

```json
{
  "engines": {
    "stt_whisper": {
      "is_multilingual": true,
      "models": [
        { "id": "tiny", "label": "Whisper Tiny", "path": "https://...", "size_mb": 75 }
      ]
    },
    "wake_vosk": { ... },
    "piper_tts": { ... }
  }
}
```

### Model Download & Extraction (`ModelDownloader.kt`)

- Models downloaded to app's files directory
- `downloadedModelIds` — Set of downloaded model IDs in settings
- `customModelPaths` — Map of custom local model paths (user-provided)
- Download preference: `wifi_only` or `wifi_and_metered`
- **Archive extraction** — `.zip` (Vosk) / `.tar.bz2` (Piper) are unpacked, then `flattenNestedDir()` collapses a single wrapper directory (`model/model/…` → `model/…`) using a `renameTo` move with a `copyRecursively` fallback. (A plain `File.copyTo` is non-recursive for directories, which used to leave a Vosk model's `am/ conf/ graph/` empty → "Failed to create a model".)
- **Validation** — `validateModel()` requires a *populated* `am/` for Vosk/zip models; if it finds a nested wrapper it self-heals by flattening in place, otherwise it deletes the corrupt dir and returns false.

### Startup Reconciliation (`VoxApplication.reconcileDownloadedModels()`)

The green "on-device" indicator is a persisted `downloadedModelIds` flag, not recomputed from disk, so it can drift (files deleted externally, a hollow extraction). At startup a background pass validates every downloaded model against disk (archive models via `validateModel`, file models via a presence check — only registry-known ids) and clears the flag for anything missing/corrupt, so it turns re-downloadable. Runs decoupled from the network fetch; loads the registry locally first (`fetchJson(force = false)`).

### Whisper Native Libraries

- Debug builds: `libwhisper.so`, `libggml.so`, `libggml-vulkan.so` bundled in APK
- Release builds: Excluded from APK, downloaded as DLC at runtime
- Vulkan probed at first launch, `vulkanIncompatible` flag set if GPU doesn't support

---

## 14. Memory Management

### VoxApplication

Implements `ComponentCallbacks2.onTrimMemory()`:

```kotlin
TRIM_MEMORY_RUNNING_LOW (10) → release Vosk models
TRIM_MEMORY_RUNNING_CRITICAL (15) → release Whisper models
TRIM_MEMORY_UI_HIDDEN (20) → release all heavy native models
```

### MemoryManagedComponent

Interface implemented by engines that hold native resources:

```kotlin
interface MemoryManagedComponent {
    fun releaseMemory()
    fun restoreMemory(context: Context)
}
```

Engines that implement this:
- `WhisperSttEngine` — releases `whisper_context`
- `WakeWordEngine` (Vosk) — releases Vosk recognizer
- `PiperTtsEngine` — releases sherpa-onnx model

---

## 15. Return-to-Previous-App

### Concept

After executing a voice command that launches an app (e.g., "play Scorpions on Spotify"), the user is returned to the app they were using before the command. The launched app continues in background (for media) or the user can switch back manually.

### Configuration

- Setting: `returnAfterActionApps: List<String>` — list of package names
- UI: Multi-select app picker in App Manager tab → "Return to previous app after action"
- Stored as JSON in DataStore (`return_after_action_apps_json`)

### Implementation (`IntentRouter.kt`)

```kotlin
fun route(intent: NluIntent): Boolean {
    val settings = settingsRepository.getSettingsSnapshot()
    val resolvedApp = AppResolver.resolve(intent, settings)

    val targetPkg = resolvedApp?.packageName
    val shouldReturnAfter = targetPkg != null && targetPkg in settings.returnAfterActionApps
    val previousApp = if (shouldReturnAfter) getForegroundPackage() else null

    val success = handler.execute(context, intent, resolvedApp)

    if (shouldReturnAfter && previousApp != null && previousApp != targetPkg) {
        CoroutineScope(Dispatchers.Main).launch {
            delay(1500)  // wait for target app to initialize
            returnToApp(previousApp)
        }
    }
    return success
}
```

- `getForegroundPackage()` — Uses `ActivityManager.runningAppProcesses` to find the current foreground app
- `returnToApp()` — Uses `PackageManager.getLaunchIntentForPackage()` with `FLAG_ACTIVITY_SINGLE_TOP`
- 1.5s delay ensures the target app has started before switching back
- Alias-safe: checks `resolvedApp.packageName` (already resolved through alias rules)

---

## 16. External Voice Trigger

### Concept

External automation apps (MacroDroid, Tasker, Automate, etc.) can trigger the voice assistant without a wake word by sending a broadcast intent. This enables integration with hardware buttons, NFC tags, schedules, or any automation trigger.

### Implementation

#### VoiceTriggerReceiver (`service/VoiceTriggerReceiver.kt`)

A `BroadcastReceiver` registered in `AndroidManifest.xml` that listens for the action `com.voxapps.commander.TRIGGER_VOICE`.

```kotlin
class VoiceTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_VOICE) return

        val repo = SettingsRepositoryImpl(context)
        if (!repo.getExternalTriggerEnabledSync()) return  // disabled in settings

        val appStateManager = AppStateManager.getInstance(repo, context)
        val isServiceRunning = appStateManager.uiState.value.isWakeWordServiceListening

        if (isServiceRunning) {
            // Service active — emit wake word event (same as real detection)
            appStateManager.onWakeWordDetected()
        } else {
            // Service not running — start with ACTION_EXTERNAL_TRIGGER
            val serviceIntent = Intent(context, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_EXTERNAL_TRIGGER
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
```

#### WakeWordService Handling

When `ACTION_EXTERNAL_TRIGGER` is received by `WakeWordService.onStartCommand()`:
1. Calls `startForeground()` with notification (required for foreground service)
2. If `wakeWordEngine` is null (service wasn't initialized), starts wake word detection first
3. Calls `onWakeWordDetected()` — same path as real wake word detection

This triggers the normal pipeline:
```
onWakeWordDetected() → stopListening() → appStateManager.onWakeWordDetected()
  → wakeWordEvents.emit(Unit) → VoiceManager.startListening() → STT → NLU → IntentRouter
```

### Security

- **Custom permission**: `com.voxapps.commander.permission.TRIGGER_VOICE` (protectionLevel: `normal`)
- Receiver is `exported=true` but requires the custom permission
- **Setting toggle**: `externalTriggerEnabled` (default: true) — user can disable in Settings → App Manager

### Manifest Registration

```xml
<permission
    android:name="com.voxapps.commander.permission.TRIGGER_VOICE"
    android:protectionLevel="normal"
    android:label="Trigger VoxCommander Voice Assistant" />

<receiver
    android:name=".service.VoiceTriggerReceiver"
    android:enabled="true"
    android:exported="true"
    android:permission="com.voxapps.commander.permission.TRIGGER_VOICE">
    <intent-filter>
        <action android:name="com.voxapps.commander.TRIGGER_VOICE" />
    </intent-filter>
</receiver>
```

### Usage

#### ADB
```bash
adb shell am broadcast -a com.voxapps.commander.TRIGGER_VOICE
```

#### MacroDroid
1. Create macro → add trigger (button, NFC, schedule, etc.)
2. Add action → **Intent Action**
3. Action: `com.voxapps.commander.TRIGGER_VOICE`
4. Target: Broadcast

#### Tasker
1. Create task → add action → **System** → **Send Intent**
2. Action: `com.voxapps.commander.TRIGGER_VOICE`
3. Type: Broadcast
4. Target package: `com.voxapps.commander`

### Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `externalTriggerEnabled` | Boolean | `true` | Enable/disable external broadcast trigger |

UI: Settings → App Manager → External voice trigger toggle

---

## 17. Dynamic JSON Configuration

VoxCommander uses four external JSON files for extensible, hot-reloadable configuration. All ship in `vox-commander/src/main/assets/` and can be updated from a remote GitHub repo at runtime — no app update required. `models.json`, `search_definitions.json`, and `intents.json` are authored at the repo root and copied into assets by Gradle; `normalization.json` lives directly in assets.

### models.json

**Location**: Repo root → copied to assets by `copyModelsJson` Gradle task (`preBuild` dependency)

**Parsed by**: `RemoteModelRegistry` (`data/remote/RemoteModelRegistry.kt`)

**Contents**:
- `schema_version` — Integer, used to detect newer versions for hot-reload
- `prompts.standard_nlu` — The NLU system prompt sent to LLM interpreters (OpenAI, Gemini, Local LLM). Contains sentence anatomy rules, domain/action taxonomy, and JSON output format
- `engines` — Map of engine key → engine definition. Each engine has `type`, `is_multilingual`, `extension`, a `capabilities` list, and a `models` array:
  - `stt_whisper` — Whisper.cpp models (tiny, base, small) with download URLs and sizes
  - `wake_vosk` — Vosk models (`voice` + `wake_word`); capabilities include `calibration`, `wake_word_text`, `model_download`
  - `wake_openwakeword` — OpenWakeWord ONNX models (bundled); capability `builtin_models`
  - `wake_porcupine` — Porcupine built-in keywords (virtual, no file); capabilities `builtin_keywords`, `builtin_models`, `requires_api_key`
  - `nlu_llm` — Local LLM (GGUF) models
  - `piper_tts` — Piper TTS voice models (language, speaker, download URL)

  Every model entry carries the same key set (`id, label, path, size_mb, size_label, lang_code, engine_type, is_remote`) — `null` where unused — for a uniform structure.

**Hot-reload**: At startup, `RemoteModelRegistry` checks `modelRepoBaseUrl` setting for a newer `models.json`. If the remote schema version is higher, it downloads and caches it (never downgrades below the bundled asset version).

### search_definitions.json

**Location**: Repo root → copied to assets by `copySearchDefinitions` Gradle task (`preBuild` dependency)

**Parsed by**: `SearchProviderRegistry` (`domain/search/SearchProviderRegistry.kt`)

**Contents**:
- `schema_version` — Integer, for hot-reload detection
- `categories` — Array of category definitions:
  - `category` — "general", "news", "knowledge", "weather"
  - `providers` — Array of provider definitions:
    - `name` — Provider display name
    - `endpoint` — API URL template
    - `requires_api_key` — Boolean
    - `parser` — Response parser type ("json", "rss", "html")
    - `parser_config` — Parser-specific configuration (JSON paths, CSS selectors, etc.)

**Hot-reload**: `SearchProviderRegistry.loadFromRemote()` fetches `search_definitions.json` from the remote repo URL (converted from GitHub URL to `raw.githubusercontent.com`). Falls back to assets copy if remote is unavailable. Local copy stored in `filesDir/search_definitions.json`.

**Adding a new search provider**: Just add a new entry to `search_definitions.json` — no code changes needed. `DynamicSearchProvider` (`domain/search/DynamicSearchProvider.kt`) creates providers from JSON definitions at runtime.

### intents.json

**Location**: Repo root → copied to assets by `copyIntentsJson` Gradle task (`preBuild` dependency)

**Parsed by**: `IntentCatalog` (`domain/intent/registry/IntentCatalog.kt`) — mirrors `SearchProviderRegistry` (init / fetchRemote / ensureLocalFile / loadFromFilesDir / saveLocalFile, schema-versioned no-downgrade).

**Purpose**: The catalog of standard Android intents probed per installed app (previously the hardcoded `KnownIntents.PROBE_MAP` in `AppRegistry.kt`). See §7 — `AppRegistry.probeSupported()`/`probeMetadata()` iterate this catalog.

**Contents**:
- `schema_version` — Integer, for hot-reload detection
- `template_action_domains` — Map of `templateAction` → domain (`navigate`→`maps`, `search`→`audio`, `send`→`messaging`)
- `taxonomy` — The NLU vocabulary (added in schema v2): `domains` (list), `actions` (flat list), and `actions_by_domain` (map). This is the domain/action list fed to the NLU prompt and the Rules UI. `IntentTaxonomy` keeps the domain/action *constants* (handlers dispatch on them) but reads these *lists* from here via `IntentCatalog`, with a single seed fallback in `IntentCatalog`. Adding a vertical (e.g. `notes`) to the LLM's vocabulary is a JSON edit; it routes via `GenericLaunchHandler` unless it needs a bespoke handler.
- `intents` — Array of intent definitions: `action` (the literal Android action string, e.g. `android.intent.action.VIEW`), `probe_uri`, `uri_template` (with `{query}`/`{destination}`/`{contact}` placeholders), `label`, `template_action`, `requires_query`, `mime_type`

`intents.json` is thus the **capability manifest**: what verticals exist (taxonomy) + what any app can be asked to do (intents).

**Hot-reload**: fetched from the remote repo at startup and via the Settings "Sync JSON" button, same mechanism as `models.json`/`search_definitions.json`. If the JSON is missing/unparseable, a compact hardcoded seed (core routing intents) keeps the app functional.

**Adding a probeable intent**: add an entry to `intents.json` — no code change. `IntentCatalogTest` is a golden test asserting each SDK action constant is transcribed byte-exact (a mistyped literal would silently never match).

### normalization.json

**Location**: `vox-commander/src/main/assets/normalization.json` (not copied from repo root — ships directly in assets)

**Parsed by**: `TextNormalizer` (`domain/voice/TextNormalizer.kt`)

**Purpose**: Corrects STT (Whisper) transcription errors before NLU processing. For example, if Whisper transcribes "Spotify" as "spotif" or "pe spotify" as "pespotify", the normalizer fixes it before the text reaches the intent interpreter.

**Contents** — 3-layer priority-based rule pipeline per language:

```json
{
  "schema_version": 1,
  "en": {
    "layer_1_replacements": {
      "rules": {
        "\\bspotif\\b": "spotify",
        "\\bwaze\\b": "waze"
      }
    },
    "layer_2_regex": {
      "rules": [
        { "pattern": "\\bpe\\s+(spotify|youtube)", "replacement": "pe $1" }
      ]
    },
    "layer_3_cleanup": {
      "rules": {
        "\\s+": " "
      }
    }
  },
  "ro": { ... }
}
```

- **Layer 1 — Static replacements**: Exact-match word substitutions (abbreviations, common misrecognitions). Applied first.
- **Layer 2 — Ordered regex**: Interceptors and sweepers with word boundary locking. Applied in order (array order matters).
- **Layer 3 — Cleanup**: Final regex pass for whitespace normalization and residual fixes.

**Loading**: `TextNormalizer.load(context)` reads from assets at startup. Supports `reload()` for testing. Rules are compiled into `Pattern` objects and cached per language.

### Build Integration

```kotlin
// app/build.gradle.kts
val copyModelsJson = tasks.register<Copy>("copyModelsJson") {
    from("${project.rootDir}/models.json")
    into("${projectDir}/src/main/assets")
}

val copySearchDefinitions = tasks.register<Copy>("copySearchDefinitions") {
    from("${project.rootDir}/search_definitions.json")
    into("${projectDir}/src/main/assets")
}

val copyIntentsJson = tasks.register<Copy>("copyIntentsJson") {
    from("${project.rootDir}/intents.json")
    into("${projectDir}/src/main/assets")
}

tasks.named("preBuild") {
    dependsOn(copyModelsJson)
    dependsOn(copySearchDefinitions)
    dependsOn(copyIntentsJson)
}
```

`normalization.json` is not copied from repo root — it lives directly in `vox-commander/src/main/assets/` since it's not hot-reloaded from remote.

---

## 18. Dependency Graph

### Core Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Jetpack Compose BOM | (latest) | UI framework |
| Material 3 | (via BOM) | Design system |
| Vosk Android | (via libs.versions) | Wake word + STT |
| Whisper.cpp | (submodule, CMake) | On-device STT |
| sherpa-onnx | v1.13.4 (JitPack) | Piper TTS |
| MediaPipe GenAI | (via libs.versions) | Local LLM (llama.cpp) |
| Google Generative AI | 0.9.0 | Gemini Nano |
| Picovoice Porcupine | 4.0.2 | Wake word engine |
| OpenWakeWord | v0.1.5 (rementia, vendored fork — `:core:wakeword`) | Wake word engine, RMS silence-gate patched |
| ONNX Runtime | 1.27.0 | ML inference for OpenWakeWord |
| Spotify App Remote | (local AAR) | Spotify media control |
| NewPipe Extractor | v0.26.3 (JitPack) | YouTube search/parsing |
| OkHttp | (via libs.versions) | HTTP client |
| Retrofit | (via libs.versions) | API client |
| Gson | (via libs.versions) | JSON serialization |
| DataStore Preferences | (via libs.versions) | Settings storage |
| Security Crypto | (via libs.versions) | Encrypted preferences |
| Room | (via libs.versions) | FastMap rules database |
| Apache Commons Compress | 1.28.0 | Piper model extraction (.tar.bz2) |
| ProcessPhoenix | 3.0.0 | App restart |
| Browser | 1.10.0 | Chrome Custom Tabs (Spotify OAuth) |

### Build Tasks

| Task | Description |
|------|-------------|
| `autoCompileWhisper` | Checks whisper.cpp upstream and recompiles via CMake if needed |
| `autoCheckVosk` | Checks for newer Vosk version on JitPack |
| `autoCheckOpenWakeWord` | Checks for a newer OpenWakeWord upstream tag and whether the RMS-gate patch would still apply (see [§2 OpenWakeWord Fork & Sync](#openwakeword-fork--sync)) |
| `copyModelsJson` | Copies `models.json` from repo root to assets |
| `copySearchDefinitions` | Copies `search_definitions.json` from repo root to assets |
| `copyIntentsJson` | Copies `intents.json` from repo root to assets |

All six tasks are dependencies of `preBuild`.

### Repositories

- **Google Maven** — AndroidX, Compose, MediaPipe
- **Maven Central** — OkHttp, Retrofit, Gson, Apache Commons, ONNX Runtime
- **JitPack** — Vosk, sherpa-onnx, NewPipe Extractor
- **Picovoice Maven** — Porcupine

OpenWakeWord is no longer pulled from JitPack — it's vendored as source (`:core:wakeword`, patched) with
its pristine upstream tracked via a git submodule. See [§2 OpenWakeWord Fork & Sync](#openwakeword-fork--sync).

---

## 19. Vox Apps Ecosystem (Cross-App Contract)

VoxCommander doubles as a **local plugin hub** ("micro-OS for offline plugins"): companion *satellite*
apps declare voice capabilities and Commander routes commands to them. There is **no runtime coupling**
— each app is an independent product; the only shared artifact is a **contracts-only** library.

> This section covers the architecture from Commander's (the consumer's) side. For a hands-on,
> copy-pasteable "build a new satellite from scratch" tutorial — Gradle setup, manifest snippets,
> a complete `VoxCommandReceiver` example, and a debugging checklist — see
> [`docs/SATELLITE_APP_GUIDE.md`](SATELLITE_APP_GUIDE.md).

### The contract module (`:core:ipc`)

A tiny Android library (`com.voxapps.ipc`) with **no logic** — just the wire protocol, compiled into
each app (like `:core:design` for theming). A third-party developer can integrate by mirroring these
strings locally; nothing forces a dependency.

| Type | Purpose |
|------|---------|
| `VoxIpc` | Constants: actions (`ACTION_COMMAND`, `ACTION_SPEAK`, `ACTION_LLM_PROCESS`, `ACTION_LLM_RESULT`, `ACTION_OCR_RESULT`, `ACTION_SCHEMA_CHANGED`, `ACTION_CAPABILITY_QUERY`), extras, ops (`OP_CREATE`, `OP_READ`, `OP_PING`, `OP_GET_SCHEMA`, `OP_EXPORT`, `OP_IMPORT`), capability meta-data keys (`META_DOMAIN`, `META_ACTIONS`, `META_LABEL`, `META_NLU_HINT`, `META_OCR_TASK`), the six shared `com.voxapps.vox.permission.*` constants |
| `VoxCommand` | Command envelope authored by Commander (`op`, `text?`, `title?`, `category?`, `domain?`, `exportScope?`, `dateFrom?`, `dateTo?`) with `toJson()`/`fromJson()` (org.json) — `dateFrom`/`dateTo` are an additive pair used only by Vox Calendar's day-scoped `OP_READ` (see below); every other satellite's `OP_READ` ignores them and behaves exactly as before |
| `VoxResult` | Satellite reply for reads (`ok`, `text`) — the notes payload, or a spoken "locked" message; also the `OP_GET_SCHEMA` reply's envelope (`text` carries a `VoxSatelliteSchema` JSON — see [Collapsed satellite extraction flow](#collapsed-satellite-extraction-flow-voxsatelliteschema) below) |
| `VoxSatelliteSchema` | A satellite's extraction contract: `needsExtractionPass`, `promptTemplate` (with an `{{INPUT}}` placeholder), `fieldSchemaVersion`, `taskId` — see below |

### Capability advertising & discovery (the handshake)

A satellite declares an **exported** `BroadcastReceiver` for `ACTION_COMMAND`, guarded by a
`signature`-level custom permission (`com.voxapps.vox.permission.COMMAND` — shared across every Vox
app, declared once in `:core:ipc`'s own manifest and folded in via manifest merger, not a per-app
name), with `<meta-data>` describing what it handles:

```xml
<receiver android:name=".receiver.VoxCommandReceiver" android:exported="true"
          android:permission="com.voxapps.vox.permission.COMMAND">
    <intent-filter><action android:name="com.voxapps.action.VOX_COMMAND"/></intent-filter>
    <meta-data android:name="com.voxapps.vox.domain"   android:value="notes"/>
    <meta-data android:name="com.voxapps.vox.actions"  android:value="create,read"/>
    <meta-data android:name="com.voxapps.vox.label"    android:value="Notes"/>
    <meta-data android:name="com.voxapps.vox.nluHint"
               android:value="If the user names a target list/category, put that category name in the category field; otherwise category=null."/>
</receiver>
```

`nluHint` is optional — see [Domain-specific NLU hints](#domain-specific-nlu-hints-nluhint) below.

On the Commander side:

- **`VoxAppsDiscovery.discover()`** — `queryBroadcastReceivers(ACTION_COMMAND, GET_META_DATA)`, reads
  each app's advertised `domain`/`actions`/`label`, and computes **`isFirstParty`** via
  `PackageManager.checkSignatures(self, target) == SIGNATURE_MATCH` (same developer key = native app).
- **`VoxSatelliteRegistry`** — a `StateFlow<List<VoxAppInfo>>` rebuilt by `refresh(context)`, called at
  **warmup** (`AppContainer.init`) and from the Integrations screen. It's the single dynamic source of
  truth for "which app owns which domain".
- **Dynamic NLU taxonomy** — `IntentCatalog.taxonomyDomains()/taxonomyActions()/taxonomyActionsForDomain()`
  merge the registry's domains/actions on top of the JSON/seed vocabulary, so the LLM can emit a
  satellite domain only when a satellite for it is installed. **Satellite verticals are NOT defined in
  `intents.json`.**
- **`OP_PING`** — a live handshake (ordered broadcast, expects `VoxResult.ok`) used by the Integrations
  UI to show a "Contract verified" status.

### Command bus (transport)

Ordered broadcasts over Binder — no Activity, no ContentProvider, no NLU on the satellite side:

- **create** — `sendBroadcast(ACTION_COMMAND, setPackage(pkg), EXTRA_PAYLOAD=VoxCommand.toJson())`,
  fire-and-forget (append; never wakes the UI).
- **read** — `sendOrderedBroadcast(..., resultReceiver)`; the satellite `goAsync()`s, reads its DB, and
  returns `VoxResult` in `setResultData`; Commander speaks it via the **TTS hook** (`TtsHookService` →
  `TtsManager.speak`, which already carries the user's rate/pitch/voice/language). The same hook is
  exposed as `ACTION_SPEAK` (permission `com.voxapps.commander.permission.SPEAK`) so any authorized app
  can ask Commander to speak.

Payloads are **plaintext over Binder** by design: Binder IPC is kernel-mediated and invisible to other
apps; encryption *at rest* stays the satellite's concern (e.g. Vox Notes uses SQLCipher). Encrypting the
payload would require sharing keys across apps and break their independence.

### Domain-specific NLU hints (`nluHint`)

`models.json`'s `SATELLITE OVERRIDE` prompt rule (see [§4 Prompt System](#prompt-system-promptproviderkt))
only covers behavior that's universal to *any* companion-app domain (create/read stripping semantics).
Anything domain-*specific* — e.g. Vox Notes' spoken `category` field — is instead declared by the
satellite itself, via the optional `com.voxapps.vox.nluHint` meta-data key, so that Commander's shared
prompt never needs a per-app edit:

- **`VoxAppsDiscovery`** reads `META_NLU_HINT` alongside domain/actions/label during discovery and stores
  it on `VoxAppInfo.nluHint`.
- **`PromptProvider.buildSatelliteHints()`** filters `VoxSatelliteRegistry.apps` down to installed
  satellites with a non-blank hint, and appends one line per app under a `Domain-specific extraction:`
  section, after the rules — e.g. `- notes: If the user names a target list/category, put that category
  name in the category field; otherwise category=null.`
- This scales to any number of satellites at negligible prompt-size cost (one short line each) and, more
  importantly, means a *future* satellite (e.g. a hypothetical expenses app with an `amount` field) never
  requires touching `models.json` — it just declares its own hint, exactly like Vox Notes does today.

### Routing hierarchy (`SatelliteHandler` + `SatelliteRouting`)

When several apps advertise the same domain (e.g. Vox Notes **and** a third-party OpenNotes both handle
`notes`), `SatelliteRouting.pick(candidates, starredPkg, explicitPkg)` — a **pure, unit-tested** function
— decides, in order:

1. **Explicit** — an app named in the utterance ("save in OpenNotes"), if it's a candidate.
2. **User star** — the default the user set in **Settings → Default Apps** (`AppResolver` reads
   `defaultAppPackages[domain]`; satellite domains are now listed there). `IntentRouter` resolves this
   into `resolvedApp` before the handler runs, so it's honored automatically.
3. **First-party** — a candidate with `isFirstParty == true` (same signing key as Commander). Vox Notes
   beats a third-party alternative **silently** — signature-based, so it can't be spoofed by app name.
   Requires every first-party app to actually share one release signing certificate (see the README's
   "Download APK" section) — this went silently unenforced for a while (each app used its own distinct
   keyAlias within the shared keystore file, which are unrelated key pairs), so this check quietly
   always returned `false` between apps in release builds until that was fixed.
4. **Single third-party** — exactly one candidate → route to it.
5. **2+ third-party, no star** — route to the first discovered and flag `ambiguous` (logged). A spoken
   "which app?" disambiguation that persists the choice as a star is a planned follow-up.

The **Integrations → Vox Apps** section lists discovered apps with their `domain • actions`, a
**First-party / Third-party** badge, and the live ping status.

### Generic LLM hook (`ACTION_LLM_PROCESS` / `ACTION_LLM_RESULT`)

The create/read command bus above only covers a satellite's own domain data. Several newer satellite
features (Vox Notes' category/note-duplicate cleanup, Vox Vision's OCR-text cleanup) need something
different: *ask Commander's LLM to process an arbitrary prompt*, without Commander needing to
understand what the feature is. That's the generic LLM hook — a second, parallel bus, symmetric to the
command bus but carrying opaque prompt/result payloads instead of structured notes data:

- **Request** — a satellite broadcasts `VoxIpc.ACTION_LLM_PROCESS` with a `VoxLlmRequest{sourcePackage,
  task, promptText, data}` JSON in `EXTRA_LLM_PAYLOAD`, guarded by the signature-level, shared
  `com.voxapps.vox.permission.LLM_PROCESS` permission. `task` and `promptText` are entirely
  owned by the caller — Commander never parses or validates them.
- **`LlmHookReceiver`** does only fast parse/validate work, then hands off to a one-time
  **`LlmHookWorker`** (`WorkManager`, not a plain `Service`) — on-device testing showed a plain
  non-foreground `Service` started from a `BroadcastReceiver` can be silently blocked by OEM/Doze
  background-execution restrictions when Commander has no visible UI, whereas `WorkManager`'s
  `JobScheduler`-backed execution is exempted.
- **`LlmHookEngineSelector.run(promptText)`** routes the raw prompt to whichever engine is currently
  configured as the user's primary AI processor (`aiProcessor` setting — the same selection
  [`IntentDecisionMap`](#4-natural-language-understanding-nlu) uses for its L2 step), calling
  `AssistantEngine.rawPrompt()` directly with **no L1/L3 fallback cascade** (this hook always targets a
  single, currently-selected engine, not the triple-brain pipeline).
- **Reply** — `LlmHookWorker` applies only generic cleanup (`NluIntentParser.cleanGenericOutput`,
  stripping markdown/prose fences) to the LLM's raw text, wraps it in a `VoxLlmResult{task, status,
  rawJson}`, and delivers it as an **explicit-intent** broadcast (`ACTION_LLM_RESULT`, targeted at
  `sourcePackage`) — only after re-checking `checkSignatures(..) == SIGNATURE_MATCH` against the
  requester, so a reply is never sent to an unrelated package. The satellite's own `LLM_RESULT`
  signature permission guards receipt on its end.
- Because Commander never interprets `task`/`promptText`/`rawJson`, a satellite can add a brand-new
  LLM-backed feature (prompt design, result parsing, and applying the result) with **zero Commander
  changes** — it just picks a new `task` string.

```
satellite ──VoxLlmRequest{sourcePackage,task,promptText}──▶ LlmHookReceiver ──▶ LlmHookWorker
                                                                                      │
                                                                    LlmHookEngineSelector.run(promptText)
                                                                                      │
satellite  ◀── VoxLlmResult{task,status,rawJson} ── explicit intent, signature-checked ┘
```

### Collapsed satellite extraction flow (`VoxSatelliteSchema`)

Voice commands to a satellite with a real structured schema (Expenses, Calendar) need **two** LLM
calls: Commander's own classification call (produces the anatomy-based `NluIntent` — see
[§4](#4-natural-language-understanding-nlu)), then a domain-specific *extraction* pass that resolves
things the classification call can't (e.g. Expenses' distributive-vs-cumulative price disambiguation,
which needs the satellite's own category list and reasoning rules loaded to resolve correctly). Notes
is the exception — its schema is simple enough that the classification call's own output already
satisfies it, so it never needs a second pass.

Earlier, this second call happened via a 3-broadcast round trip per command: Commander →
satellite (`VOX_COMMAND`, carrying only `logicalSubject`, discarding the rest of the anatomy) →
satellite → Commander (`ACTION_LLM_PROCESS`, a **freshly built** prompt, unrelated in structure to the
first call's output) → Commander → satellite (`ACTION_LLM_RESULT`). Each hop is real IPC cost, worse
when the satellite process isn't already running. This is now collapsed:

- **The satellite declares its own contract.** `VoxIpc.OP_GET_SCHEMA` (same request-response channel
  as `OP_PING`/`OP_EXPORT`/`OP_IMPORT`) returns a `VoxSatelliteSchema`: `needsExtractionPass` (required,
  never defaulted — a missing value is a malformed-contract error state, not silently treated as
  `false`), `promptTemplate` (the satellite's full, self-owned prompt text — reasoning rules + field
  schema + current dynamic context all pre-interpolated, with exactly one `{{INPUT}}` placeholder),
  `fieldSchemaVersion` (see KSP-generated schema below), and `taskId` (the satellite's own
  `LlmTasks` constant, so Commander can stamp the right value on the eventual `VoxLlmResult` without
  knowing anything about the satellite's task-naming scheme).
- **Fetched proactively, cached, never per-command.** Integrations → Vox Apps gets a **Refresh**
  button per satellite (alongside the existing Test/ping button) that calls `VoxSatelliteRegistry
  .refreshSchema()` — a Hub-style flash-retry (launch the satellite's own activity via
  `FLAG_ACTIVITY_NEW_TASK` to clear Android's "stopped" state, then retry) if the satellite isn't
  immediately reachable. The result is cached in `VoxSatelliteRegistry` (DataStore-backed, survives
  process death) and used for **every** subsequent voice command — no TTL, no automatic
  re-fetch, manual-refresh-only by design. The reason this is worth doing proactively rather than
  per-command isn't raw IPC latency (usually fine) — it's that these satellites are background
  companion apps normally launched *by* voice command rather than kept open, so the common case for a
  cold command is the satellite process being dead; without a cache, every such command would pay the
  flash-retry cost **on the voice-command hot path**.
- **Warm-cache dispatch.** `SatelliteHandler.create()` reads the cached schema; if
  `needsExtractionPass` is `false` (or no cache exists yet — first-run falls back to today's
  unmodified `VOX_COMMAND`/`OP_CREATE` flow, self-served by the satellite exactly as before), nothing
  changes. If `true`, Commander substitutes `NluIntent.toDecompositionText()` (the *full* anatomy —
  action/subject/modifiers/context/target — not just `logicalSubject`, fixing the earlier
  handoff-drops-most-of-the-decomposition problem) into `schema.promptTemplate`, runs the extraction
  call itself via `LlmHookEngineSelector`, and delivers the result via the **same**
  `ACTION_LLM_RESULT`/`VoxLlmResult` wire shape the generic LLM hook already used — so a satellite's
  existing `LlmResultReceiver` needs zero changes to consume it. Net effect on a warm-cache command:
  zero live IPC for the extraction step itself, one fire-and-forget delivery broadcast at the end
  (which doesn't need flash-retry — it's the same kind of explicit broadcast that already worked
  without it, flash-retry is only for the Refresh button's synchronous fetch).
- **Satellite-initiated cache correction.** The one exception to manual-only refresh: if a satellite's
  own dynamic context changes as a side effect of normal use (e.g. Expenses auto-creating a category
  from a voice command, or a user editing categories in Expenses' own UI), the cached prompt template
  is now wrong. Rather than Commander guessing at the new state, the satellite pushes a corrected
  `VoxSatelliteSchema` via `VoxIpc.ACTION_SCHEMA_CHANGED` (fire-and-forget, the one
  satellite-initiated broadcast in this whole contract — everywhere else Commander initiates) the
  instant the mutation commits; `SchemaChangedReceiver` auto-applies it to the cache immediately. A
  precise, verified-event push, not a poll or timer.
- **KSP-generated field schema.** `ExpenseParsePromptBuilder`/`CalendarEventParsePromptBuilder`'s
  field-listing prose used to be hand-typed and could silently drift out of sync with the actual
  parser (`ExpenseParseResultParser.Parsed`, etc). A new `@VoxExtractionSchema(version)` annotation
  (`:core:schema-annotations`) plus a KSP `SymbolProcessor` (`:core:schema-processor`) generates a
  `Generated<ClassName>Schema` object — `VERSION`/`FIELD_SCHEMA_JSON` — from the annotated class's
  *primary constructor parameters only* (deliberately not `getAllProperties()`, which would also pick
  up computed fields like `Parsed.itemsSumMismatch`), recursing into nested data classes and `List<T>`
  element types. `fieldSchemaVersion` mirrors `models.json`'s `schema_version` bump convention —
  informational only, shown in Integrations for debugging, never drives invalidation. Applied
  uniformly to all three satellites' output shapes (`ExpenseParseResultParser.Parsed`,
  `CalendarEventParseResultParser.Parsed`, and Notes' `Note` — the last one exists purely for
  uniformity, since `needsExtractionPass = false` means it's never actually used on the wire).

```
Warm cache:
Commander (call #1: classification, in-process)
   │ needsExtractionPass? (from cached VoxSatelliteSchema)
   ├─ false (Notes) ──────────────────────────────────▶ VOX_COMMAND/OP_CREATE (unchanged)
   └─ true (Expenses/Calendar)
        │ schema.buildPrompt(intent.toDecompositionText())
        ▼
      Commander (call #2: extraction, in-process, via LlmHookEngineSelector)
        │
        ▼
      ACTION_LLM_RESULT{task,status,rawJson} ──▶ satellite's existing LlmResultReceiver

Refresh (Integrations button, not per-command):
Commander ──OP_GET_SCHEMA (request-response, flash-retry if unreachable)──▶ satellite
   ◀── VoxSatelliteSchema{needsExtractionPass,promptTemplate,fieldSchemaVersion,taskId} ──┘
   (cached in VoxSatelliteRegistry, DataStore-backed)

Satellite-initiated correction (the one push in this contract):
satellite ──ACTION_SCHEMA_CHANGED{VoxSatelliteSchema}──▶ SchemaChangedReceiver (auto-applies)
```

### Multimodal photo attachment (receipt/document scans)

Independent of the voice-command flow above: Expenses' and Notes' *scan* flows (Vision OCR → generic
LLM hook cleanup) can now attach the actual photo alongside the OCR text when the configured engine
supports images — additive, not a replacement. Skipping OCR entirely in favor of the photo was
considered and rejected: multimodal image-reading beating OCR+text on dense receipt/document text is
an unvalidated assumption, and a raw photo is strictly more data leaving the device (and more cloud
vision tokens — OpenAI/Gemini price images by pixel-dimension tiling, not JPEG quality or color depth)
than OCR-extracted text. So OCR always runs; the photo, when enabled, is one more input on the same
single LLM call — never a second call.

- **Capability declaration.** `RemoteModelRegistry.isMultimodal(processor)` checks a hardcoded set for
  the two cloud processors that are actually multimodal today (OpenAI, Gemini Cloud — Gemini *Native*
  is on-device and not yet implemented for the LLM hook at all, so it's excluded), falling back to
  `hasCapability(engineKey, "multimodal")` for JSON-defined local engines (none declare it yet). A new
  generic `VoxIpc.ACTION_CAPABILITY_QUERY` (ordered broadcast, `CapabilityQueryReceiver` on
  Commander's side, `VoxCapabilityClient.isMultimodal()` client-side) exposes this to any first-party
  app — deliberately separate from `VoxSatelliteSchema`/`OP_GET_SCHEMA`, since this is global Commander
  engine state, not per-satellite data.
- **Resolution, not quality, is what controls token cost.** Vision's Settings gets two new
  preferences: **"Send photo to AI"** (off by default — real token cost on top of free local OCR) and
  **"Photo detail for AI"** (Low/Medium/High → 768/1024/1536px long edge). Only resolution changes
  LLM token cost for an attached image; JPEG compression quality and color depth don't factor into
  either OpenAI's or Gemini's tiling-based image tokenization, so neither is exposed as a
  cost-relevant setting. When "Send photo to AI" is on, capture produces a **second**, separately
  downscaled JPEG (`downscaleToLongEdge`) alongside the existing full-resolution one, and both are
  handed back via a new `VoxOcrResult.aiImageUri` field (kept distinct from `imageUri`, which stays
  full-resolution for the receipt/record display a human might view later).
- **Per-satellite opt-in.** Expenses gets two independent toggles — "Attach photo on scan" and
  "Attach photo on retry" (retry re-sends already-staged OCR text after a failed parse without
  re-scanning; a separate toggle since it's a distinct, less frequent code path) — both off by
  default. Notes gets one ("Attach photo on scan"; it has no stub/retry mechanism at all, confirmed by
  tracing its code, not assumed). `VoxLlmRequest` gained an `attachmentUri` field the satellite fills
  in only after its own toggle is on *and* Vision actually provided a downscaled copy; `LlmHookWorker`
  forwards it to `LlmHookEngineSelector.run(promptText, imageUri)`, which threads it to whichever
  engine is selected — `OpenAiInterpreter`/`GeminiCloudInterpreter` attach it (base64 data URI for
  OpenAI's chat-completions format, `content { image(bitmap) }` for the Gemini SDK), every other
  engine implementation ignores the parameter.
- **Cross-app URI grants require a local copy, not a re-grant.** A plain read grant on someone else's
  FileProvider URI (e.g. what Vision grants Notes/Expenses for `aiImageUri`) can't be re-granted
  onward to a third app (Commander) — only the URI's actual owner can grant it to an arbitrary
  package. So Expenses/Notes always copy the granted image into their own storage (Expenses:
  `filesDir/receipts/<name>_ai.jpg`, persisted so a later retry can reuse it without re-invoking
  Vision; Notes: a short-lived `cacheDir/ai_scans/` copy, since it has no retry mechanism to serve)
  before re-sharing it via their own FileProvider. Notes never had a FileProvider before this feature
  and needed one added from scratch.

### Vox Vision (OCR satellite)

`vox-vision` (`com.voxapps.vision`) is the second satellite in this repo — unlike Vox Notes, it never
receives voice commands (its `VisionCommandReceiver` only answers the discovery `ping`); it's purely a
**note producer**. Two ways it's invoked:

- **Direct explicit-intent launch, not a broadcast** — a satellite wanting a scan (e.g. Vox Notes) calls
  `context.startActivity(Intent().setClassName(VoxIpc.VISION_PACKAGE, VoxIpc.VISION_ACTIVITY_CLASS))`
  with a `VoxOcrRequest{sourcePackage, task, hint}` JSON in `EXTRA_OCR_PAYLOAD`. This is deliberate:
  camera capture needs a live foreground window, and Android's background-activity-launch (BAL)
  restriction is evaluated against the **calling** app's foreground state, not the callee's — so a
  foreground-to-foreground `startActivity` (button tap → `VisionActivity`) hits no restriction at all.
  An earlier design routed this through a `BroadcastReceiver` that tried to self-launch its own
  Activity with no visible caller UI backing it, which BAL silently blocked; that receiver was removed.
- **`VisionActivity`** is `android:launchMode="singleTask"` with an `onNewIntent` override, so a second
  scan request while it's already running redelivers into the same instance instead of losing the
  pending-request extras (both the launch mode *and* the override are required together — either alone
  is insufficient).
- Vision's own OCR pipeline (camera capture → brightness-blob auto-capture → OpenCV quad crop →
  on-device PaddleOCR) hands raw text to Vox Vision's own copy of the generic LLM hook (its
  `LlmResultReceiver`, `LlmTasks.OCR_CLEANUP`) to get a clean title/body, then forwards it to Vox Notes
  as a create command. When launched as a pending-request target (`hint`/`task` present), an
  auto-triggered capture skips straight to submission with no manual tap — a manual capture always
  still requires one, since there's no guarantee every field is already correct.
- **Multimodal photo attachment** (Settings → "Send photo to AI" + "Photo detail for AI") — off by
  default. When on, capture also produces a downscaled JPEG (`downscaleToLongEdge`, 768/1024/1536px
  by detail level) alongside the existing full-resolution one, handed back to the caller as
  `VoxOcrResult.aiImageUri` in addition to the existing `imageUri` — see [Multimodal photo attachment]
  (#multimodal-photo-attachment-receiptdocument-scans) above for the full flow and why resolution
  (not JPEG quality) is the only setting that actually affects LLM token cost.

### Vox Calendar (day-linking extension)

`vox-calendar` (`com.voxapps.calendar`, Kotlin/AGP namespace `com.voxapps.calendarapp`) is the fourth
satellite (domain `calendar`) and the one that stretches the command bus beyond create/read: its
day-summary sheet needs to show *another* app's data inline, not just its own.

- **Outbound (Calendar → Notes/Expenses)** — tapping a day sends a plain **explicit intent** (not a
  broadcast) carrying `VoxIpc.EXTRA_SELECTED_DATE`, mirroring how Vision launches its scan request; the
  target Activity reads the extra in `onCreate` and calls its own already-existing date-filter setter.
  No new IPC surface — this direction reuses each app's existing UI-filtering code.
- **Inbound (Calendar reads Notes/Expenses for a day)** — this is what the additive `VoxCommand.dateFrom`/
  `dateTo` fields exist for: when both are present, `NotesReadResponder`/`ExpensesReadResponder` filter
  their snapshot to that window (reusing the same `NoteFilter`/`ExpenseFilter` the UI already uses) and
  return a compact `{"count": N, "items": [{"title","timeMillis"}]}` JSON in `VoxResult.text` instead of
  the normal human-readable TTS string. Absent dates, `OP_READ` behaves exactly as it did before this
  feature — the change is purely additive.
- **ICS import/export** is a separate, non-IPC feature living entirely inside `vox-calendar` (Settings →
  ICS import/export), via the `net.sf.biweekly:biweekly` library — spec-correct `VEVENT`/`VTODO`/
  `RRULE` handling without hand-rolling RFC 5545. It shares no code with the Hub JSON export/import path
  below; the two are deliberately independent formats for independent purposes (interop vs. backup).
- Voice-created events/tasks ("dentist in a week") go through the same 4-file LLM-prompt pattern Vox
  Expenses established (prompt builder → request sender → result parser → `LlmResultReceiver`), with
  layer-name resolution via exact match → `:core:textmatch` fuzzy match → configurable default layer.

### Vox Hub (backup/restore client, not a satellite)

`vox-hub` (`com.voxapps.hub`) is the odd one out: it never implements a `VoxCommandReceiver` and
registers no NLU domain, so it never appears in Commander's taxonomy or Integrations screen. It's purely
a **consumer** of the `export`/`import` actions every other satellite already exposes:

- **`VoxAppsDiscovery.discover()`** (the same discovery call Commander itself uses) finds every
  installed app advertising `export` and/or `import` — Hub has zero hardcoded app list, so a new
  satellite shows up automatically the moment its manifest meta-data declares those actions.
- **`VoxDataTransferClient.requestExport(context, pkg, scope)`/`requestImport(context, pkg, json)`**
  send the same `OP_EXPORT`/`OP_IMPORT` ordered broadcasts a satellite's own `VoxCommandReceiver`
  already answers — Hub adds no new wire protocol, just a client-side helper.
  `ExportImportUtil.summarize()` is the one spot with a small hardcoded per-app JSON-key list (for the
  cosmetic "3 notes, 2 categories"-style import preview) — every other Hub code path is fully generic.
- Because Hub holds no Room database, its own settings (currently just the shared theme preference) are
  the only thing it persists locally.

### Key classes

| Class | Path |
|-------|------|
| `VoxIpc` / `VoxCommand` / `VoxResult` | `core/ipc/src/main/java/com/voxapps/ipc/` |
| `VoxLlmRequest` / `VoxLlmResult` | `core/ipc/src/main/java/com/voxapps/ipc/` |
| `VoxOcrRequest` / `VoxOcrResult` | `core/ipc/src/main/java/com/voxapps/ipc/` |
| `VoxSatelliteSchema` | `core/ipc/src/main/java/com/voxapps/ipc/VoxSatelliteSchema.kt` |
| `VoxCapabilityClient` (multimodal capability query) | `core/ipc/src/main/java/com/voxapps/ipc/VoxCapabilityClient.kt` |
| `VoxAppsDiscovery` / `VoxAppInfo` / `VoxSatelliteRegistry` (schema cache) | `domain/integration/` |
| `SchemaChangedReceiver` (satellite push) / `CapabilityQueryReceiver` | `domain/integration/`, `service/` |
| `SatelliteRouting` (pure decision) | `domain/integration/SatelliteRouting.kt` |
| `SatelliteHandler` (dispatch, collapsed extraction call) | `domain/intent/handler/SatelliteHandler.kt` |
| `NluIntent.toDecompositionText()` (full anatomy, not just `logicalSubject`) | `domain/intent/model/NluIntent.kt` |
| `PromptProvider.buildSatelliteHints()` (nluHint → prompt) | `domain/intent/interpreter/PromptProvider.kt` |
| `TtsHookReceiver` / `TtsHookService` | `service/` |
| `LlmHookReceiver` / `LlmHookWorker` / `LlmHookEngineSelector` | `service/`, `domain/intent/` |
| `ImageAttachmentUtil` (reads/base64-encodes an attached image) | `domain/intent/interpreter/ImageAttachmentUtil.kt` |
| `@VoxExtractionSchema` / KSP `SymbolProcessor` (generated field schema) | `core/schema-annotations/`, `core/schema-processor/` |
| Satellite receiver (Notes) | `vox-notes/.../receiver/VoxCommandReceiver.kt` |
| Vision's LLM result receiver | `vox-vision/.../receiver/LlmResultReceiver.kt` |
| `VisionActivity` (`singleTask` + `onNewIntent`) | `vox-vision/src/main/java/com/voxapps/vision/VisionActivity.kt` |
| `DocumentCropper` (Otsu live-bounds + strict-quad crop) | `vox-vision/.../ocr/DocumentCropper.kt` |
| `downscaleToLongEdge` (AI-attachment photo resize) | `vox-vision/.../ui/VisionScreen.kt` |
| `MultimodalAttachmentResolver` (Expenses' scan/retry photo-attach gate) | `vox-expenses/.../domain/llm/MultimodalAttachmentResolver.kt` |
| Day-scoped read + ICS export/import | `vox-calendar/.../receiver/VoxCommandReceiver.kt`, `vox-calendar/.../domain/ics/` |
| Hub's export/import client | `core/ipc/.../VoxDataTransferClient.kt`, `vox-hub/.../ui/HubScreen.kt` |

---

## 20. Shared UI Modules (`:core:calendar`, `:core:apppicker`)

Two more code-reuse-only Gradle modules (same shape as `:core:design`/`:core:wakeword` — no shared
runtime state, just library code compiled into whichever apps need it) shipped alongside the Vox Apps
ecosystem work: a calendar/agenda view and a searchable app picker, each consumed by more than one app.

### `:core:calendar`

A month-paged, per-day agenda view (`CalendarView.kt`) built on Compose's `HorizontalPager` — no custom
paging/fling physics needed, the pager provides deceleration for free. Consumed by both `vox-notes` and
`vox-expenses` as an opt-in "Calendar view" setting (off by default), replacing their chronological
`LazyColumn` list.

- **`CalendarItem`** — the only thing the module knows about a caller's data: `id: Any` +
  `dateTimeMillis: Long`. Each app wraps its own Room-backed model in a `@JvmInline value class`
  (`NoteCalendarItem`, `ExpenseCalendarItem`) implementing this interface — the module never depends on
  either app's data classes.
- **`CalendarDateUtils.bucketByDay()`** — buckets items by day for the *whole* month, including empty
  days (so scroll position is never ambiguous), and separately computes a small peek window (default 3
  items) into the tail of the previous month / head of the next — rendered at reduced alpha
  (`CalendarMonthView`'s `GrayedPeekSection`) and tappable to jump straight to that month+day
  (`CalendarView.navigateToItem`, via `PagerState.animateScrollToPage` + `LazyListState.animateScrollToItem`).
- **`MonthYearHeader`** — a fixed, non-scrolling "July 2026"-style label above the day list (month/year
  is *not* repeated on every day row, unlike a typical calendar list) plus a "Today" `IconButton`
  (`Icons.Filled.Today`) that jumps back to the current month/day. Both live inside a `Box` (not a
  `Row`) so the button floats at `Alignment.CenterEnd` without pushing the centered header text off-axis.
- **`MonthTransitionIndicator`** — a fading center popup naming the month being swiped toward, driven
  directly by `PagerState.currentPageOffsetFraction` (no separate animation) so it always tracks the
  live gesture rather than a fixed-duration transition.
- **Locale**: `CalendarView`/`CalendarMonthView`/`DayHeader`/`MonthYearHeader` all take an explicit
  `locale: Locale` parameter (default `Locale.getDefault()`) rather than reading `Locale.getDefault()`
  internally, so a caller can pass its own in-app language setting — matters because the device's system
  locale and the app's own language setting (each app has its own `LanguageManager`, incompatible
  between apps, so this module can't own localized strings itself) can differ.
- **Known limitation** — `CalendarOverscrollPaging.kt` (a `NestedScrollConnection` meant to trigger a
  month change when overscrolling past the top/bottom of a month, with its own vertical transition) is
  implemented but **not wired into `CalendarView`** — installing it froze ordinary vertical scrolling
  entirely on-device (root cause not found). Months currently only change via the pager's own
  left/right swipe gesture or a peek-item tap. The dead code is left in place as a documented follow-up
  rather than deleted.

`vox-expenses` additionally layers bank/vendor filters and an amount-ascending/descending sort
(`ExpenseFilter.kt`, `ExpenseFilterSortSheet.kt`) on top of its list — sorting by amount isn't
chronological, so it doesn't fit the per-day calendar layout; selecting an amount sort disables the
calendar view for as long as the sort is active (a dismissible chip lets the user clear it and return).

### `:core:apppicker`

An expandable app-picker card (`AppPickerCard.kt`) — header row summarizing the current selection, tap
to expand a search box + all/user/system filter dropdown + scrollable list — ported out of
`vox-commander`'s original `AppSelectorDropdown.kt` (see §7) into a module both `vox-commander` and
`vox-expenses` now depend on.

- **`AppPickerEntry`** — minimal app-agnostic model (`packageName`, `displayName`, `isSystemApp`).
  Callers map their own richer type into this: `vox-commander` maps its domain/intent-aware
  `AppRegistry.AppEntry` (dropping `domains`/`uriTemplates`, which the picker UI doesn't need);
  `vox-expenses` maps a plain `PackageManager` launcher-app query result.
- **`AppPickerStrings`** — every UI string the card needs, supplied by the caller from its own
  `LanguageManager` (each app has its own, mutually incompatible type — the shared module stays free of
  either app's localization dependency by taking pre-resolved strings instead of a language code).
- Two overloads: single-select (`selectedPackage: String?`) for `AppManagerTab`/`RulesManagerScreen`,
  and multi-select-with-optional-default-star (`selectedPackages: List<String>`, `onSetDefault:
  ((String?) -> Unit)? = null`) for `DefaultAppsTab` and `vox-expenses`' notification-source picker.
  `onSetDefault = null` omits the star column entirely — the right choice for "which apps' notifications
  should we inspect for payment info," which has no meaningful "default" concept, unlike "which app
  plays audio by default."
- `vox-commander`'s `AppSelectorDropdown.kt` keeps its exact original public signatures (so none of its
  three call sites needed to change) but is now a thin wrapper: it keeps satellite/domain-aware
  candidate filtering (`VoxSatelliteRegistry`) and Spotify-OAuth interception locally, maps its richer
  `AppRegistry.AppEntry` down to `AppPickerEntry`, and delegates all rendering to `AppPickerCard`.

**`vox-expenses`' launcher-apps cache** (`domain/apps/LauncherAppsCache.kt`) — mirrors
`vox-commander`'s `AppRegistry` cache pattern (§7: scan once, persist as JSON, reload on next launch)
but is deliberately a much simpler plain object: `vox-expenses`' picker only ever needs a flat launcher-
app list (`queryIntentActivities(ACTION_MAIN, CATEGORY_LAUNCHER)`), not `AppRegistry`'s per-app
intent-capability probing, so the scan is cheap enough to run synchronously in `ExpensesContainer`'s
`init` block (before any UI composes) rather than needing a dedicated splash/progress screen. The
persisted cache (`ExpensesSettings.appCacheJson`, DataStore-backed) means only the *first-ever* launch
pays the scan cost; every launch after that just deserializes the cached JSON. A manual "Rescan Apps"
button in the notification-capture settings screen re-scans on demand (e.g. after installing a new
banking app) and re-persists the result.

---

## 21. Data Hygiene (`:core:datahygiene`)

A small contracts-and-logic Gradle module (`com.voxapps.datahygiene`, Android-library — needs
`org.json.JSONObject` at runtime, mirroring `:core:ipc`'s exact reasoning for that module shape) that
gives every satellite one shared way to stop garbage strings from reaching the database, and one
shared policy for *when* to clean silently vs. ask the user first.

**The bug this closes**: `org.json.JSONObject.optString(key)` (no fallback arg) silently turns a
genuine JSON `null` into the literal string `"null"` — `JSONObject` stores JSON null as the
`JSONObject.NULL` sentinel, and that sentinel's `toString()` returns `"null"`; `optString` stringifies
whatever `opt()` returns. A well-formed LLM reply (`"vendor": null`) and a malformed one
(`"vendor": "null"`) both corrupt identically, with no exception to catch it. First found live in
vox-expenses (a notification-captured expense had `vendor` literally displaying "null"); on
investigation, the same bug class existed independently in vox-calendar's `CalendarEventParseResultParser`
(a private, case-*sensitive* reimplementation of the same guard with no punctuation check) and
vox-notes' `NoteScanCleanupResultParser` (no guard at all) — both fixed as part of this module landing.

**`FieldCleaner`** — the one shared predicate: `clean(value, fieldName?, recordLabel?)` trims and
discards to `null` if the value is blank, the literal string `"null"` (case-insensitive), or pure
punctuation/whitespace with no letters or digits; `cleanRequired(value, fallback, ...)` is the same
predicate for a non-nullable field, coercing to `fallback` instead of `null`; `isDirty(value)` is true
only when a field has real garbage content that `clean` would discard — not just because it's blank —
so a manual-UI confirm dialog (below) never fires on a routine empty field.

**`optCleanString`** — a `JSONObject` extension (`optCleanString(key, fieldName, recordLabel)`)
delegating to `FieldCleaner.clean`, replacing every satellite's own ad-hoc null-string guard at the
JSON-parsing boundary.

**`RecordSanitizer<T>`** — the per-entity contract (`sanitize(record): T`, `isDirty(record): Boolean`)
each satellite implements once per data class it wants covered (`ExpenseSanitizer`,
`CalendarEntrySanitizer`, `NoteSanitizer`). **`RecordSource`** (`LLM` / `HUB_IMPORT` / `MANUAL_UI`) and
the shared **`decideForSave(record, source)`** extension function encode the actual policy so it's
never reimplemented per app:

| `RecordSource` | Behavior |
|---|---|
| `LLM` | Always sanitized, always proceeds — no human in the loop to ask. |
| `HUB_IMPORT` | Always proceeds untouched — another install's already-validated data; rewriting it on import would be its own bug. |
| `MANUAL_UI` | Proceeds untouched if clean; if `isDirty()`, returns `SaveDecision.ConfirmCleanup` instead of saving, so the calling screen can show "auto-clean or cancel and fix it yourself" before committing. |

Gating always happens in the **caller** (the LLM-result receiver, or the edit screen's Save handler),
never inside the shared repository's `add*`/`update*` methods — those are called by both manual saves
and Hub import with no way to distinguish the two internally, so sanitizing there would incorrectly
rewrite imported data too. Each app's existing inline `?.trim()?.takeIf { it.isNotEmpty() }` cleanup
inside the repository stays as-is and is unrelated to this stricter guard.

See the [Satellite App Guide §6.6](SATELLITE_APP_GUIDE.md#66-data-hygiene-cleaning-records-before-insert)
for the wiring pattern with code examples.

---

*This documentation reflects the codebase as of July 2026. For the latest changes, refer to the git history.*
