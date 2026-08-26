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
20. [Shared UI Modules (`:core:calendar`, `:core:apppicker`, `:core:design` color picker)](#20-shared-ui-modules-corecalendar-coreapppicker-coredesign-color-picker)
21. [Data Hygiene (`:core:datahygiene`)](#21-data-hygiene-coredatahygiene)
22. [Project Structure](#22-project-structure)
23. [Release Process & CI Automation](#23-release-process--ci-automation)
24. [Record Creation (`:core:recordflow`, `:core:suggestions`)](#24-record-creation-corerecordflow-coresuggestions)
25. [Document Reading (`:core:docread`)](#25-document-reading-coredocread)
26. [Where a Record Happened (`:core:location`)](#26-where-a-record-happened-corelocation)
27. [Backup, and Sync Between Two Phones (`:core:backup`, `:core:voxconnect`)](#27-backup-and-sync-between-two-phones-corebackup-corevoxconnect)
28. [Home-Screen Widgets (`:core:widget`)](#28-home-screen-widgets-corewidget)

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
interface IWakeWordEngine : VoxEngine {
    fun startListening(): Boolean
    fun stopListening()
    fun stopService()
}
```

Identity and lifecycle come from `VoxEngine`/`BaseVoxEngine` (engine key, `load`/`unload`/`release`,
observable `EngineState`, the shared load mutex); the interface adds only what listening itself needs.
The model and the wake phrase arrive together as a `ModelSpec.WakeWordModel` (`modelId`,
`entryPoint` — null for a keyword built into the engine — `keyword`, `language`), so all three engines
get both at load time and "the user changed the wake word" is expressed as `load(newSpec)`.

`ParallelWakeWordEngine` (`:core:wakeword`) is the openWakeWord path's own arrangement rather than a
fourth engine: the mel spectrogram, the embedding model and the per-word classifier run as a pipeline
over one audio stream, so several wake words are watched for at once without decoding the audio once
per word. `DetectionMode` and `WakeWordScore` are what a caller tunes and reads — a score per word
rather than a single boolean, because "did it hear something" and "which of these did it hear" are
different questions.

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
- **Adaptive noise gate** — shares `:core:audio`'s `AdaptiveNoiseGate` with OpenWakeWord's patched
  `AudioRecorder.kt` (see [OpenWakeWord Fork & Sync](#openwakeword-fork--sync)) rather than each engine
  maintaining its own noise-floor math; margin is derived from the same Wake Word Sensitivity setting.
- **AEC** — Optional Acoustic Echo Cancellation for wake word detection during media/TTS playback (`wakeWordAecEnabled`).

#### Picovoice Porcupine (`PorcupineWakeWordEngine.kt`)

- Uses Picovoice's Porcupine SDK. The 13 built-in keywords (alexa, jarvis, computer, …) are defined as **non-remote models in `models.json`** (`wake_porcupine` engine) — no longer injected in Kotlin. Selecting one sets it as the wake word.
- Requires a Picovoice Access Key (`picovoiceAccessKey`); the Service page disables **Start** and shows a warning until the key is entered (driven by the `requires_api_key` capability).
- Also supports custom `.ppn` model files in assets.

#### OpenWakeWord (`OpenWakeWordEngine.kt`)

- Fully open-source, ONNX-based wake word detection. Models are `wake_openwakeword` entries in `models.json` (bundled in `assets/openwakeword/`).
- **Startup warmup** — Detections in the first 1.0 s after each `start()` are ignored. Right after `start()` the mel/embedding feature buffers aren't primed and emit spurious high scores; without this guard the detect → command → re-arm cycle self-triggers into a loop.
- **Vendored + patched fork (`:core:wakeword`)** — vendored as source rather than consumed as the
  `xyz.rementia:openwakeword` JitPack artifact. Upstream runs full ONNX inference (mel-spectrogram → embedding → classifier) on
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

Sensitivity is baked in at engine `initialize()`, so changing it requires an engine reload. The Service page shows a **confirmation dialog** on change and, if the service is running, persists the value (awaiting the write and the reactive-state propagation) and then hot-reloads the engine so the new threshold applies immediately.

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
| `core/wakeword/src/main/kotlin/.../audio/AudioRecorder.kt` | Patched file #1. An RMS silence gate drops buffers below an energy floor *before* the short→float conversion and *before* anything is emitted — so `WakeWordEngine`'s ONNX inference never runs on silence. Layered with an *adaptive* margin above the live ambient noise floor (`:core:audio`'s `AdaptiveNoiseGate`, also shared by the Vosk engine — see below), so the gate keeps closing in a sustained noisy room where a fixed floor alone stops helping. Both the fixed floor (`rmsGate`) and the adaptive margin (`noiseGateMargin`) are derived from the user's Wake Word Sensitivity setting via `WakeWordSensitivity.openWakeWordRmsGate()`/`.noiseGateMargin()`; `0f`/upstream defaults preserve stock behavior. |
| `core/wakeword/src/main/kotlin/.../WakeWordEngine.kt` | Patched file #2 (the vendored library's own engine class — not to be confused with `vox-commander`'s separate Vosk `WakeWordEngine.kt`). Just forwards `rmsGate`/`noiseGateMargin` through its public constructor to `AudioRecorder`, which is the only place that actually acts on them. |
| `core/wakeword/patches/0001-rms-silence-gate.patch`, `0002-wakeword-engine-params.patch`, `0003-audioprocessor-score-logging.patch` | The three patches — the two files above plus `AudioProcessor.kt`'s per-buffer prediction-score logging (scores above 0.05 only, so "near misses" show up in logs without flooding them) — each maintained as a real unified diff (not just "the current file"); regenerate all of them together with `./scripts/vox patches regen wakeword`. |
| `core/wakeword/NOTICE` / `LICENSE` | Apache 2.0 attribution chain (OpenWakeWord, Google Speech Embedding Model, ONNX Runtime). |

**Keeping it in sync with upstream releases:**

- `scripts/check_openwakeword_version.sh` — local, non-destructive dry-run: checks for a newer upstream
  tag and whether the stored patches would still `git apply --check` cleanly against it, without touching
  the working tree either way. Runnable on demand as the `autoCheckOpenWakeWord` Gradle task —
  deliberately **not** a `preBuild` dependency: "a newer upstream exists" is a maintenance fact, not a
  build fact, and it is delivered by the scheduled sync workflows. `./gradlew :vox-commander:checkUpstream`
  runs every upstream check (Vosk, NewPipe, OpenWakeWord, OpenCV, PaddleOCR, whisper) in one command.
- `.github/workflows/sync-openwakeword.yml` — weekly scheduled (+ manual dispatch) workflow: on a new
  upstream tag, bumps the submodule, fully re-vendors `core/wakeword`'s sources, and tries to `git apply`
  the stored patch. If it applies cleanly *and* the module compiles + unit tests pass, it opens a PR
  that's already ready to review/approve — nothing to hand-merge in the common case. It only surfaces a
  manual-merge PR (with the reject hunk attached) if the patch genuinely conflicts with an upstream
  change to the same lines. Never auto-merges.
- The same pattern (scheduled sync workflow, PR-per-update, never auto-merged) covers every tracked
  upstream dependency (`vendor/docquad-sdk` is the deliberate exception — a language port cannot be
  patch-tracked, so `./scripts/vox check docquad` reports drift for a human to judge): Whisper.cpp (`sync-whisper.yml`, monthly — compiles/tests only, deliberately never
  publishes the production `.so` DLC), llama.cpp (`sync-llama.yml`, monthly, a day after the whisper
  run), Vosk (`sync-vosk.yml`, weekly) and NewPipe Extractor (`sync-newpipe-extractor.yml`, weekly) —
  both binary JitPack dependencies, so their PRs bump `gradle/libs.versions.toml` rather than apply a
  patch — plus OpenCV (`sync-opencv.yml`, weekly) and the PaddleOCR fork (`sync-ppocr-sdk.yml`, weekly)
  for the vendored native SDKs.

---

## 3. Speech-to-Text (STT)

### Whisper.cpp Integration

- **Native libraries**: `libwhisper.so` (ggml linked in statically, OpenCL backend included with embedded Adreno kernels —
  compiled via CMake with `BUILD_SHARED_LIBS OFF`) plus `libomp.so`, its one shared dependency
- **Engine**: `WhisperCppSttEngine` in `domain/engine/whisper/`
- **Models**: Downloaded on-demand from HuggingFace (`ggml-tiny.bin`, `ggml-base.bin`, `ggml-small.bin`)
- **Release builds**: the two whisper libs are excluded from the APK via variant-scoped
  `jniLibs.excludes` (`androidComponents.onVariants`) and downloaded as real, user-facing DLC
  (~88 MB for libwhisper.so + libomp.so on arm64, fetched by `WhisperEngineManager` when the user
  enables Whisper, verified against
  digests recorded in the APK as `assets/whisper-libs.sha256`) — the model download above is the
  user-visible part of the same mechanism, The llama.cpp runtime is packaged by `voxDlc` mode: in `minimal` (the
  default) `libllama.so` (~6 MB, no `libomp.so` — OpenMP is compiled out and the library has no
  non-platform `DT_NEEDED`) ships inside every APK; in `full` it is excluded and fetched on demand
  by `LlamaEngineManager` from its fingerprint-addressed `llama-libs-<pin12>` release when a local
  LLM engine is selected — see [§13](#13-model-management). LiteRT-LM's `liblitertlm_jni.so`
  (~21 MB) is the exception to all of this: it ships inside the APK in both modes, because the
  vendor SDK loads it by name and only the APK's own library directory is searched. onnxruntime, Vosk, and sherpa-onnx-jni
  aren't DLC in that sense: they're mandatory libraries the app needs to function. In `minimal` DLC
  mode (the default) they ship inside the APK; in `full` mode they're excluded the same way and
  silently fetched once at first launch by `core:nativelibs` (see
  `docs/BUILD_TIME_DEPENDENCIES.md`).
- **OpenCL**: opt-in GPU acceleration via the ggml OpenCL backend inside `libwhisper.so` — a
  per-engine toggle, off by default, proven per device by a sandboxed compatibility probe (see
  [§4 GPU Acceleration](#gpu-acceleration-opencl))

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

The NLU pipeline uses a three-level redundant attempt mechanism:

```
L1: FastMap (Regex rules) — Instant, non-ML matching
  ↓ miss
L2: Primary AI Attempt — User's chosen primary engine (Cloud or Local)
  ↓ failure (timeout, network error, or model crash)
L3: Offline Fallback Attempt — User's configured secondary engine (usually Local)
```

#### L1: FastMap (`FastMapEngine.kt`)
- Regex-based pattern matching against user-defined rules.
- Confidence: always 1.0 (exact match).
- Stored in Room (`FastIntentEntity`).
- Editable via UI (Settings → Rules).

#### L2: Primary Engine
The first AI attempt. User selects via `aiProcessor` setting:

| Processor | Engine | Description |
|-----------|--------|-------------|
| `OPENAI` | `OpenAiInterpreter` | OpenAI Chat Completions API (Cloud) |
| any `local_llm` key with `backend: "llamacpp"` | `LocalLlmInterpreter` | On-device LLM (llama.cpp, GGUF, grammar-constrained) |
| any `local_llm` key with `backend: "litertlm"` | `LiteRtLlmInterpreter` | On-device LLM (LiteRT-LM, `.task`/`.litertlm`, constrained decoding) — gated on `googleServicesEnabled` |

Both on-device interpreters implement `LocalLlmEngine` (`backendId`, `lastErrorReason`, `preload`),
and `AiEngineResolver` maps a key to the one whose `backendId` matches the engine's declared
`backend`. A key that declares none resolves to the first — what it resolved to when there was only
one implementation — so a remote schema older than the app keeps working. Before loading a model an
interpreter asks its peers to release theirs; a peer serving a request declines, so two
gigabyte-class models are never resident at once.

All interpreters implement `AssistantEngine`:

```kotlin
interface AssistantEngine {
    suspend fun processCommand(spokenText: String, modelFilterLang: String?): NluIntent?
}
```

#### L3: Offline Fallback Mechanism
If L2 fails (e.g., no internet for Cloud engines, or a Local engine crash), the system automatically retries using the engine configured in `defaultIntentFallbackProcessor`. This ensures the assistant remains functional in challenging environments. It skips the retry if the fallback engine is the same as the primary one.

### NluIntent Data Model

```kotlin
data class NluIntent(
    val actionVerb: String,           // "play", "search", "say" (original language)
    val logicalSubject: String?,      // "Scorpions", "Japan" (the entity)
    val modifiers: List<String>,      // ["quickly"], ["slowly"] (how)
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
`Examples:` marker in `models.json`, or the LLM silently never sees it.** An override placed after
`Examples:` is cut before the model ever reads it, and every satellite `create` command then loses its
category/content extraction with no error anywhere — see the tests in `PromptProviderTest.kt` (`a rule appended after Examples never reaches the model`,
`the real models json prompt keeps SATELLITE OVERRIDE before the Examples cut`), which read the actual
`remote-schemas/commander/models.json` and assert the rule survives the cut.

**Shared rule vs. per-satellite hint.** `SATELLITE OVERRIDE` (in `models.json`) is intentionally
domain-agnostic — the universal create/read stripping semantics for *any* companion-app domain — and
does not need to grow as more satellites are added. Anything domain-*specific* (e.g. Vox Notes' spoken
`category`) instead belongs in that satellite's own `nluHint` manifest declaration, surfaced via
`buildSatelliteHints()` — see [§19 Domain-specific NLU hints](#domain-specific-nlu-hints-nluhint).

### Local LLM Engines

Two runtimes serve the on-device LLM, chosen per engine key by the schema's `backend` field.

#### llama.cpp

`LocalLlmInterpreter` runs GGUF models through llama.cpp, vendored as a git submodule at
`vox-commander/src/main/cpp/llama.cpp` with its Android CMake build config in
`vox-commander/src/main/cpp/llama-build/` (compiled to `libllama.so` by the `autoCompileLlama`
Gradle task — see [§18](#18-dependency-graph)). The Kotlin side talks to it through the
`com.voxapps.llamacpp.LlamaBridge` interface (`LlamaBridgeImpl` binds the JNI in
`vox-commander/src/main/cpp/llama_jni.cpp`); the release-build runtime download is
`LlamaEngineManager`'s job ([§13](#13-model-management)).

- **Two KV sequences (slots).** `LlamaBridge.complete` takes a `slot` parameter: `SLOT_NLU` (0) for
  grammar-constrained NLU completions and `SLOT_RAW` (1) for free-text raw-prompt completions (the
  satellite LLM hook — [§19](#19-vox-apps-ecosystem-cross-app-contract)). Each slot keeps its own
  cached prompt prefix in the KV, so the two kinds of call stop evicting each other's resident
  prompt — under one sequence, every hook call evicted the preloaded NLU prefix and the next voice
  command repaid the whole prefill (and vice versa). The slots share one KV pool of `n_ctx` cells
  (4096 — every model in the lineup is served with at least a 4096-token context); when a call plus
  the *other* slot's resident prefix cannot coexist, the other slot is evicted and repays its
  prefill on its own next call.
- **Longest-common-prefix KV reuse.** Per slot, the bridge compares the incoming prompt's tokens
  against the cached ones, keeps the longest common prefix resident, and decodes only from the
  divergence point. There is no per-call teardown; the next call trims the context back to the
  shared prefix.
- **Stable, unscoped system prompt.** For the local engine the NLU system prompt is deliberately
  *not* scoped to the utterance (`PromptProvider.getNluSystemPrompt` is called with an empty
  `spokenText`): per-utterance domain/app scoping saves prompt tokens, but a prompt that varies with
  the spoken text diverges early and repays most of the prefill per command, while a stable prompt is
  prefilled once and every command pays only its own `Input:` tail. The prompt still changes — and
  the cache rebuilds from the divergence point — when its real inputs change (installed apps, custom
  domains, search providers, the language hint, the schema-served template).
- **Preload prefill.** `LocalLlmInterpreter.preload()` warms the engine up front — runtime load,
  model mmap, and one dummy decode that leaves the system prompt's KV prefix resident — so the first
  real command doesn't pay the full prefill (~25s of prompt evaluation for the ~1900-token NLU
  prompt on a mid-range phone, measured).
- **Action-first GBNF grammar.** Every NLU completion is constrained by a GBNF grammar built in
  Kotlin (`buildNluGrammar`): the `action`/`domain` pair is emitted as a single combined rule —
  action first, with each action's alternative constrained to the domains that actually declare it
  (per `IntentTaxonomy.getActionsForDomain`, plus `launch` over every domain) — so the sampler
  cannot produce an action/domain combination outside the taxonomy, and the remaining `NluIntent`
  fields are optional keys in a fixed order.

### GPU Acceleration (OpenCL)

#### LiteRT-LM

`LiteRtLlmInterpreter` runs `.task` and `.litertlm` models through Google's `litertlm-android` AAR
(the SDK sniffs the container, so both formats load through one `EngineConfig`). It is Google-built
software, so both its engine keys declare the `google_service` capability and nothing about them is
reachable until the user turns on *Google on-device support*; neither is ever the derived default.

- **No rewind API.** LiteRT-LM 0.15.0 exposes no reset/clone/checkpoint, and a `Conversation`'s KV
  cache only grows — reusing one across unrelated commands degrades within two or three calls, since
  the ~2000-token system prompt plus a turn or two already approaches the context cap. Every call
  therefore closes the conversation and rebuilds it from the cached `systemInstruction`. That repays
  the prompt's prefill each time but never the one-time XNNPACK weight-cache compile.
- **Constrained decoding.** `ResponseFormat.json(schema)` with `enableResponseFormat = true`, the
  `domain`/`action` fields enum-constrained to the taxonomy — the same guarantee llama.cpp's grammar
  gives, expressed in the SDK's own terms.
- **Raw prompts run unconstrained.** A satellite hook's prompt carries its own framing, so
  `rawPrompt` opens a fresh conversation with no system instruction, no sampler config and no output
  cap: the reply budget is whatever `maxNumTokens` (4096) leaves after the prompt.
- **Packaging.** `liblitertlm_jni.so` (~21 MB) ships inside the APK in both `voxDlc` modes, unlike
  every other native library here. The SDK loads it with `System.loadLibrary("litertlm_jni")`, which
  searches only the APK's own native library directory — a copy downloaded into `filesDir` cannot
  satisfy it, because we do not control the load call the way our own whisper/llama wrappers do.

### GPU acceleration

Covers both on-device engines. `libwhisper.so` and `libllama.so` are hybrid CPU+OpenCL builds
(ggml's OpenCL backend with Adreno-tuned kernels embedded at build time) — one library carries
both backends, and the backend is chosen per model load, so the CPU path is always available as
the fallback. GPU use is a **per-engine boolean**, not an engine of its own: two
"GPU acceleration (Experimental)" switches in Settings → Advanced → the Engine & Model Management
card (`whisperGpuEnabled`, `llamaGpuEnabled`), both off by default. No engine picker contains a
GPU entry.

- **OpenCL driver resolution.** Both libraries link against an in-repo import shim
  (`vox-commander/src/main/cpp/opencl-shim/opencl_shim.c`) that forwards every CL entry point
  through `dlopen` of the vendor driver at first call; the manifest declares
  `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` so the driver is
  visible to the app's namespace. A device with no OpenCL driver runs on the CPU — the library
  still loads. The build's headers come from the pinned `vendor/OpenCL-Headers` submodule, so the
  GPU inputs are repo-pinned with no host packages involved.
- **Backend selection (llama).** `LlamaBridge.loadModel` takes `nGpuLayers` — `0` runs entirely
  on the CPU, `-1` offloads every layer to the GPU — passed through to
  `llama_model_params.n_gpu_layers` in `llama_jni.cpp`. `LocalLlmInterpreter.setupLlm` computes
  the wish as `llamaGpuEnabled && !llamaGpuIncompatible` and folds a `loadedGpu` flag into its
  reload invalidation, so a toggle flip reloads the model on the other backend through exactly
  the path a model change takes. `LlamaBridge.lastTimings` (`nativeLastTimings`, backed by
  `llama_perf_context`) reports prompt-eval and decode timings separately. Whisper's half is
  `WhisperCppSttEngine`, which resolves `whisperGpuEnabled && !whisperGpuIncompatible` at context
  load.
- **Compatibility is proven per device, not assumed.** Enabling a toggle arms a one-shot probe:
  `GpuProbe` binds `GpuProbeService`, which runs in a separate `:gpuprobe` process and performs a
  real GPU inference — whisper transcribes one second of silence with the active voice model;
  llama decodes under a `root ::= "XOK"` sentinel grammar on a tiny quantized fixture bundled as
  an APK asset (`gpu_probe_model.gguf`) — no network involved, and the multi-GB active model is
  never duplicated into a second process. The switch shows a progress/verdict modal while the
  probe runs (`GpuTestModal`). Verdicts are `COMPATIBLE`, `INCOMPATIBLE` (which also snaps the
  toggle back off), `NO_GPU_BACKEND` (the dlopen found no vendor driver — no GPU path exists on
  this device), and `UNDECIDED`.
- **A probe-process death is attributed, not assumed.** The probe process is an ordinary LMK
  target, so a death without a reply is classified through
  `ActivityManager.getHistoricalProcessExitReasons` (API 30+): a crash reads as `INCOMPATIBLE`,
  a kill (LMK, user, dependency death) as `UNDECIDED`. On API 29, or with no exit record, the
  death is treated as `INCOMPATIBLE` — in a process whose only work is the inference under test,
  that is the overwhelmingly likely cause.
- **`UNDECIDED` persists an attempt count and never auto-refires.** Re-enabling the toggle re-arms
  the probe until `MAX_GPU_PROBE_ATTEMPTS` (3); past the cap, enabling skips the probe and the
  runtime crash cookie guards the first real use.
- **Capacity check.** Before offloading, `LocalLlmInterpreter` compares the model's size against
  the GPU's reported memory budget; a model larger than the budget stays on the CPU with a
  recorded skip reason.
- **Runtime crash cookie.** A native GPU crash mid-inference cannot be caught in-process, so
  every *unverified* GPU inference journals a per-engine cookie to disk synchronously before it
  starts (`setGpuRuntimeAttemptSync`) and clears it after; a success sets `*GpuRuntimeVerified`
  and ends the bookkeeping. `AppContainer` checks for a surviving cookie at construction and
  attributes the previous main-process death through the OS exit record — only an attributed
  `REASON_CRASH_NATIVE` counts a strike (`*GpuCrashStrikes`), and at `GPU_CRASH_STRIKE_LIMIT` (2)
  the verdict latches `*GpuIncompatible` and the toggle turns off.
- **"Test again."** Each switch shows a verdict line (incompatible / verified / probe passed) and,
  once a verdict exists, a "Test again" action (`AppStateManager.clearGpuVerdict`) that forgets
  this device's verdict so the next enable re-probes from scratch.
- **Diagnostics.** The Native Library Inventory carries two capability rows, "Whisper GPU" and
  "LLM GPU", answered by the stored incompatible verdict rather than by looking for a file — the
  OpenCL backend lives inside each engine's library, and the vendor driver is resolved by dlopen
  at first use.
- **Benchmark.** The benchmark runs paired "Local LLM (CPU)" and "Local LLM (GPU)" rows (a
  "Skipped (Hardware Incompatible)" row when the verdict is latched), each with an NLU case and a
  ~1500-token receipt-scale raw-prompt case; the detailed report carries prompt-eval and decode
  tok/s separately. Both backends drive the one shared interpreter singleton under its mutex,
  with `benchmarkGpuOverride` forcing the backend — never a second instance loading the same
  multi-GB model.
- **`WHISPER_VULKAN` engine-key rewrite.** `SettingsRepositoryImpl.migrateWhisperVulkanRetirement`
  (one-shot, guarded by `gpuStateMigrated`, called from `AppContainer`) rewrites a stored
  `voiceProcessor` of `WHISPER_VULKAN` into `stt_whisper` plus `whisperGpuEnabled`;
  `normalizeEngineKey` performs the same remap on backup import.

### NluIntentParser (`NluIntentParser.kt`)

Post-processes LLM output:
- Validates domain and action against `IntentTaxonomy`
- Normalizes target app names
- Infers missing fields from anatomy (e.g., if action_verb is "search" → domain=search)
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

**The probe catalog is data-driven** — see `IntentCatalog` (`domain/intent/registry/IntentCatalog.kt`), which loads `intents.json` (`remote-schemas/commander/` → assets → filesDir → remote, hot-reloadable like `models.json`; see §17). A compact hardcoded seed is used only if the asset read fails. The behavioral handlers (§6) stay in code; the catalog only feeds them.

`AppSelectorDropdown.kt` (the domain-app picker UI used by the Default Apps / App Manager / Rules Manager screens) is now a thin wrapper around the shared `:core:apppicker` card component — it keeps Commander-specific concerns (satellite/domain-aware candidate filtering, Spotify OAuth interception) and delegates all rendering (search, all/user/system filter, checkbox+star list) to the shared module. See [§20 Shared UI Modules](#20-shared-ui-modules-corecalendar-coreapppicker-coredesign-color-picker).

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
*backend*, not just a local intent) is defined entirely in `api_integrations.json`
(`remote-schemas/commander/`, copied into `assets/schemas/` by `copyShippedSchemas` — same convention
as `intents.json`/`models.json`, see §17) and executed by two generic engines.
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
- `device_select` (step type only) — declarative device preference:
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
- The instances and regions are declared in `remote-schemas/commander/media_services.json` and parsed
  by `MediaServiceRegistry` (`domain/media/MediaServiceRegistry.kt`) — no compiled-in instance list.
  Public Piped instances go down and get replaced regularly; swapping one is a schema edit, not an
  app release (see [§17 media_services.json](#media_servicesjson)).
- `PipedSearchHelper` reads `MediaServiceRegistry.endpoints(...)` and falls back across them
- User selects instance + region in settings
- `searchAndPlay()` — searches Piped, gets videoId, launches `youtu.be/{id}` in target app

#### NewPipe Extractor (`NewPipeExtractorHelper.kt`)

- On-device YouTube parsing (no external API) — a device-local library, so it stays compiled in
  rather than schema-declared: it has no endpoint a schema could usefully carry
- Uses `com.github.teamnewpipe:NewPipeExtractor:v0.26.4`
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
| General | DuckDuckGo (Instant Answer), Wikipedia, OpenAI |
| News | Google News, GNews, Currents API, NewsAPI |
| Knowledge | Wikipedia, OpenAI |
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
| API/Cloud | `apiKey`, `engineApiKeys` (per-engine key map) |
| Language | `language`, `voiceLanguage`, `voiceLanguageAutoDetect`, `modelFilterLang` |
| Voice Engine | `voiceProcessor`, `activeVoiceModelId`, `isWhisperSystemEnabled` |
| Intent Engine | `aiProcessor`, `activeIntentModelId` |
| Engine gating | `cloudIntelligenceEnabled` (gates every engine whose schema declares `runtime: "cloud"`), `googleServicesEnabled` (gates every engine declaring the `google_service` capability) — both default off. Switching either off also clears every stored selection it gated back to schema defaults: `AppStateManager.setCloudIntelligenceEnabled()`/`setGoogleServicesEnabled()` compute the gated engine keys from the schema and call `SettingsRepository.clearEngineSelections`. Which engines a gate covers is the schema's call, never a key list in code. |
| Wake Word | `wakeWord`, `wakeWordEnabled`, `wakeWordModelPath`, `wakeWordEngineType`, `wakeWordSensitivity`, `wakeWordAecEnabled` |
| Offline Fallback | `offlineFallbackTimeout`, `defaultOfflineModel`, fallback processors/models |
| Default Apps | `defaultAppPackages`, `domainAppPackages`, `customDomains`, `domainAppFilters` |
| Downloads | `downloadedModelIds`, `customModelPaths`, `downloadPreference` (`wifi_only` / `wifi_and_metered`) |
| Media | `spotifyClientId`, `pipedApiUrl`, `pipedRegion`, `youtubeUrlEngine`, `returnAfterActionApps` |
| TTS | `ttsEnabled`, `ttsEngineType`, `ttsSpeechRate`, `ttsPitch`, `ttsAudioFocusMode`, `piperVoiceModelId` |
| Aliases | `appAliasRules` |
| Location | `locationHomeTownLat`, `locationHomeTownLon`, `locationCacheTtl`, `locationAlwaysUseHomeTown` |
| GPU acceleration | `whisperGpuEnabled`, `llamaGpuEnabled` — the user's choice, and the only GPU fields that ride a backup. The rest is this device's verdict, excluded from export: per engine, `*GpuIncompatible`, `*GpuProbeDone`, `*GpuProbeAttempts`, `*GpuRuntimeAttempt` (the crash cookie), `*GpuRuntimeVerified`, `*GpuCrashStrikes` (see [§4 GPU Acceleration](#gpu-acceleration-opencl)). `gpuStateMigrated` guards the one-shot `WHISPER_VULKAN` rewrite and rides exports like the other migration flags. |
| Logging | `debugLoggingEnabled`, `debugToastsEnabled` |

### Sync vs Async

- **Sync getters** (`getXxxSync()`) — Use `runBlocking { dataStore.data.first() }`. Called from non-suspend contexts (UI, service).
- **Async setters** (`suspend fun setXxx()`) — Use `dataStore.edit { }`. Called from coroutines.

---

## 12. UI Architecture

### Compose + Material 3

- Single-activity architecture (`MainActivity`)
- `SettingsContent` — a settings menu with per-topic subpages (see below)
- `ListeningScreen` — Main voice interaction screen with overlay
- Navigation via Compose Navigation

### Settings Menu & Pages

`SettingsContent` (`SettingsScreen.kt`) holds a private `SettingsPage` enum (`MENU`, `GENERAL`,
`MODELS`, `SERVICE`, `APP_MANAGER`, `INTEGRATIONS`, `INTEGRATIONS_APPS`, `INTEGRATIONS_MEDIA`,
`INTEGRATIONS_SEARCH`, `PERMISSIONS`, `ADVANCED`, `LOGS`, `DIAGNOSTICS`, `BACKUP`). Integrations is
itself a submenu rather than three unrelated composables stacked behind one entry, so `backTarget(page)`
— not a hardcoded `MENU` — is what the back arrow and `BackHandler` both use.

**Two shapes, and which one a page gets follows from where it sits.** A *menu* page is a scrollable
column of plain `ListItem` entries separated by full-bleed `SettingsSectionHeader` bands from
`:core:design`, each entry opening one subpage. A *final* page — one that holds settings rather than
routes to them — has no bands, no dividers and no bare headings: every section is a
`SettingsSectionCard`, an elevated card carrying its own title and the same padding as every other,
so a group is delimited by the logic it contains rather than by a rule drawn across the screen. A
section that already arrives as its own card from `:core:design` (notifications, backup, the log
viewer, the location picker, permission items) is left alone rather than double-wrapped. The
convention holds across all six apps; `ThemeSettingsBody`, `SchemaUpdatesSection` and
`LogsSettingsTab` card themselves in `:core:design` so no caller has to.

| Menu section | Entry (page) | File | Content |
|---|---|---|---|
| General | General | `GeneralSettingsTab.kt` | Language, voice-language auto-detect; theme settings render at the end of the page via the shared `ThemeSettingsBody` (`:core:design`) |
| Engines & Models | AI & Models | `ModelsSettingsTab.kt` | Voice/intent engine selection, model downloads, imports, fallbacks |
| Engines & Models | Service | `ServiceSettingsTab.kt` | Wake word engine/model config, sensitivity, service start/stop |
| Apps & Integrations | App Manager | `AppManagerTab.kt` | Default apps per domain, media session permission, return-to-previous-app, external trigger |
| Apps & Integrations | Integrations | `SettingsScreen.kt` (submenu) | Routes to the three below |
| ↳ | Connected apps | `IntegrationsTab.kt` | Spotify OAuth, Vox Apps discovery/refresh |
| ↳ | Media services | `PipedSettingsSection.kt` | YouTube URL backend, Piped instance/region |
| ↳ | Search providers | `SearchSettingsSection.kt` | Provider categories, location field |
| System | Permissions | `PermissionsSettingsTab.kt` | Runtime permissions management |
| System | Advanced | `AdvancedSettingsTab.kt` | Offline fallback (its timeout now beside the "clear default" that acts on it), the Engine & Model Management card — the cloud/Google consent toggles (the Google one labeled "Google on-device support", key `google_services_title`), the two per-engine "GPU acceleration (Experimental)" switches with their verdict line and "Test again" action ([§4](#gpu-acceleration-opencl)) — maintenance, and the tutorial. Logging moved to its own page. |
| System | Logs | shared `LogsSettingsTab` (`:core:design`) | The debug-logging and debug-toast switches plus `LogViewerCard` — its own page in every app now, rather than three hand-rolled copies riding at the bottom of an unrelated one |
| Data | Backup | `BackupSettingsSection.kt` | `VoxBackupSettingsCard` alone |
| Data | Diagnostics | `BenchmarkSettingsTab.kt` | Engine benchmark, native library inventory, runtime diagnostics |

### Reusable Components

| Component | File | Usage |
|-----------|------|-------|
| `AppSelectorDropdown` | `ui/components/AppSelectorDropdown.kt` | Single + multi-select app picker with search |
| `ConnectionTestIndicator` | `:core:design/ConnectionTestIndicator.kt` | ✅/❌/⚠️/spinner status for API tests |

### The picklist family (`:core:design/picklist/`)

Choosing one of N things is three shared components, in `:core:design` so every app gets them —
one anchor, one menu, and one place for a related API-key field or connection test:

| Component | What it is |
|---|---|
| `Picklist` | The flat one-of-N picker. Slots for the anchor (`PicklistButtonAnchor`, `PicklistCompactAnchor`, `PicklistFieldAnchor`), an optional "none" entry, a per-item note and leading icon, an action row, and a `below` slot for anything that belongs under the selection. |
| `GroupedPicklist` | The grouped variant, used where items arrive in labelled sections. Unchanged in behaviour — it was moved here, not rewritten, because it carries the per-row download/delete/progress UI. |
| `ServicePicklist` | `Picklist` plus everything a *declared service* says about itself. |

`ServicePicklist` renders, in a fixed order and only when the selected entry declares each part:
notes → credential field (`requiresCredential`) → `ConnectionTestCard` (`probeSpec()` non-null) →
a `models` slot (`hasDownloadableModels`). An entry declaring none of them draws nothing beneath the
button, calls no lambda and makes no request — an on-device engine, the platform TTS, a local model
runtime. Before this, four screens assembled the same three pieces by hand in three different orders,
and one of them tested the credential a registry had last been handed rather than the one on screen.

The model list is a *slot* rather than something the component draws: per-row download state is
something only an app knows how to produce. Where a credential is stored is likewise the caller's
(`credentialFor` / `onCredentialCommit`), because a search provider borrows the credential of the
engine it is built on rather than owning one.

### `ServiceEntry` (`:core:services`)

The same idea arrives in three shapes — an engine key to look up in the model registry, a provider
name to look up in the search registry, and an object parsed straight out of a schema. `ServiceEntry`
is the one vocabulary they all answer in: `id`, `labelKey`/`fallbackLabel`, `runtime`,
`requiresCredential`, `credentialOwnerId`, `apiKeyUrl`, `hasDownloadableModels`, and
`probeSpec(credential)`. `DeclaredService` is the data-class implementation for registries whose own
type is a key or a name; a schema-parsed class implements the interface directly.

**Declarations only.** What a service *is* belongs there; what is currently true about it does not —
the stored credential, whether a model is on disk, whether this device can run it, all stay live
lookups at the call site. A snapshot of those inside an entry is exactly how a screen once came to
test a stale credential.

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

### Imported models are models (`domain/model/ImportedModel.kt`)

A model the user imports from storage is a row in the same list as a downloaded one, with the same
weight: selected the same way, deleted by the same trash icon, and loaded **because it is selected**
rather than because it exists.

That last part is worth stating plainly: what is selected is what loads. `EngineSpecs.localSpec`
resolves the selection, so an imported model runs when the imported id is selected and a registry
model runs when a registry id is — the row in the list and the file in use never disagree.

- **Id scheme** — `ImportedModelId`: every new import gets the slugged form
  `custom:<engineKey>:<lang-or-empty>:<slug>` — always four segments, so an empty language keeps its
  position and a slug can never be misread as a language. The slug is the filename stem sanitized to
  `[a-z0-9._-]` (which also keeps `:` out of it). The legacy one-slot forms `custom:<engineKey>` /
  `custom:<engineKey>:<langCode>` are still parsed and selectable until the one-shot migration
  (`MULTI_IMPORT_MIGRATED`) rewrites them. The id is also the key the model's path is stored under in
  `CUSTOM_MODEL_PATHS_JSON`, so the two can never disagree — a stored selection is recognised as an
  import without consulting anything and the engine is read back out of the id.
- **Offered per declaration** — the import button appears only for engines whose schema declares the
  `custom_model_import` capability. An imported row is labeled with its filename plus an
  `" — imported"` suffix (translation key `model_imported_suffix`) and its measured on-disk size.
- **Selection decides loading** — a selected imported id whose file has gone yields *null* rather
  than silently loading the registry model instead.
- **Import validation** (`ModelDownloader.importCustomModel`) returns an `ImportOutcome`:
  `Accepted(file, fromArchive)`, `WrongKind(picked, expected)`, `Empty` or `Failed(message)`. The
  file picker is filtered to the extension the schema declares for that engine; vendor archives are
  accepted and unzipped; the user is told why a rejection happened and offered the deletion of the
  source archive; for per-language engines the language is asked with the same shared picklist.
- **Archive safety** — extraction confines every entry to the target directory (`confine()`), so a
  crafted archive cannot write outside the model folder. See the note on what this does *not* cover
  in [§17](#17-dynamic-json-configuration).

### Startup Reconciliation (`VoxApplication.reconcileDownloadedModels()`)

The green "on-device" indicator is a persisted `downloadedModelIds` flag, not recomputed from disk, so it can drift (files deleted externally, a hollow extraction). At startup a background pass validates every downloaded model against disk (archive models via `validateModel`, file models via a presence check — only registry-known ids) and clears the flag for anything missing/corrupt, so it turns re-downloadable. Runs decoupled from the network fetch; loads the registry locally first (`fetchJson(force = false)`).

### Whisper Native Libraries

- Debug builds: `libwhisper.so` and `libomp.so` bundled in APK
- Release builds: Excluded from APK, downloaded as DLC at runtime (~88 MB for the two arm64 libs,
  digest-checked against `assets/whisper-libs.sha256`)
- GPU (OpenCL) use is a per-engine opt-in toggle, proven per device by a sandboxed compatibility
  probe — see [§4 GPU Acceleration](#gpu-acceleration-opencl)

### Llama Runtime Library (`LlamaEngineManager`)

`libllama.so` is packaged by `voxDlc` mode: bundled inside the APK in `minimal` (the default),
excluded and downloaded on demand in `full` when a local LLM engine is selected. The `full`-mode
release is addressed by a **build fingerprint**, not the submodule commit. `libllama.so`'s bytes
come from four inputs — the llama.cpp submodule pin, the JNI bridge (`llama_jni.cpp`), the CMake
build config (`llama-build/`), and the OpenCL import shim (`opencl-shim/`) — and the published tag
must move when any of them does, so `scripts/llama_build_pin.sh` hashes the tree
state of all four (the submodule gitlink plus the build-input paths) into one 40-hex pin; the
release tag is `llama-libs-<pin12>`. The publish script, the release-workflow gate, and the Gradle
digest-recording task all consume that one script, so they can never derive different addresses for
the same tree. The APK records the pin and the published release's digests as generated assets
(`assets/llama-libs.commit`, `assets/llama-libs.sha256`); `LlamaEngineManager` downloads from the
recorded tag's release and verifies each library against the recorded digest.

---

## 14. Memory Management

### VoxApplication

Implements `ComponentCallbacks2.onTrimMemory()`. Any trim signal — `TRIM_MEMORY_BACKGROUND`,
`TRIM_MEMORY_MODERATE`, `TRIM_MEMORY_RUNNING_LOW`, `TRIM_MEMORY_RUNNING_CRITICAL`, or
`TRIM_MEMORY_UI_HIDDEN` — triggers the same response: release the three heavy native holders via
`releaseForMemoryPressure()`:

- `VoiceManager` (Whisper/Vosk STT contexts)
- `TtsManager` (Piper's sherpa-onnx model)
- `LocalLlmInterpreter` (the loaded llama.cpp model)

### MemoryManagedComponent

The contract for components that may hold heavy native resources:

```kotlin
interface MemoryManagedComponent {
    fun releaseForMemoryPressure() { /* no-op by default */ }
}
```

One method, default no-op — lightweight components (API-based engines, system TTS) need to override
nothing. `VoxEngine` and `AssistantEngine` both extend it, so every engine and interpreter is one.
A released resource reloads transparently on next use (lazy re-init), and a release is deferred while
an inference is actively running, so a trim signal never tears down a mid-transcription or
mid-generation native call.

---

## 15. Return-to-Previous-App

### Concept

After executing a voice command that launches an app (e.g., "play Scorpions on Spotify"), the user is returned to the app they were using before the command. The launched app continues in background (for media) or the user can switch back manually.

### Configuration

- Setting: `returnAfterActionApps: List<String>` — list of package names
- UI: Multi-select app picker in the App Manager page → "Return to previous app after action"
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

VoxCommander uses external JSON files for extensible, hot-reloadable configuration. They ship in the
APK's assets **and** are served from a repository at runtime, so the same file is both what the app
ships with and what it can be corrected by — no app update required.

### Where schemas live (`remote-schemas/`, `:core:services`)

Every schema in the family lives in one folder at the repo root:

```
remote-schemas/commander/   read by vox-commander
remote-schemas/expenses/    read by vox-expenses
remote-schemas/shared/      read by more than one app
```

**The folder is the list.** An app's `copyShippedSchemas` Gradle task copies `<its own>/*.json` plus
`shared/*.json` into `src/main/assets/schemas/`, and nothing names individual files — dropping a JSON
into a folder ships it, and moving it between folders changes which apps read it.

`SchemaRepo` in `:core:services` holds the arrangement in one place: `DEFAULT_BASE_URL` (the
repository serving the schemas when nothing else is configured), `FOLDER`, `ASSET_FOLDER`, `SHARED`,
and `appFolder` — set once by each `Application` before any registry starts, so one app cannot fetch
another's files. **Each app can follow its own schema repository**, which is what makes a fork usable
without touching the apps.

### Signing: schemas are dynamic, but not substitutable

These files are fetched and adopted **unattended at every launch** (`useRemoteSchemas` defaults to
`true`), and they declare engine endpoints — where the app sends speech and the user's own API keys —
and 102 model download URLs. Whoever can serve that path could redirect all of it at the next launch,
with no app update and nothing for a user to accept. The SHA-256 that `RemoteSchema` already used
compares a download against the *previous download*: it answers "did this change?", never "is this
genuine?".

The apps embed an ECDSA P-256 public key (`SchemaSignature`) and the repository publishes
`remote-schemas/manifest.json` — every schema path and its hash — with `manifest.json.sig` over it.
One signature covers the whole manifest, so adding or removing a file is as detectable as editing one.

| Situation | Result |
|---|---|
| Official repo, signed | adopted, `Source.ACCEPTED` |
| Official repo, unsigned or tampered | **refused**, `Refreshed.Unsigned`; the current copy stays |
| Official repo, replayed older manifest | **refused** — the manifest's `serial` must exceed the last accepted |
| A fork the user configured | adopted, `Source.UNVERIFIED` |
| An exact mirror serving the original manifest | adopted as **signed** — the signature travels with the files, not the host |

The point is provenance, not content: a schema may say anything, which is the feature — what the
signature establishes is that a third party cannot say it. A fork is accepted precisely because the
user chose that URL, and marked so the distinction is visible.

Two details that make it hold:

- **A fresh install has a rollback floor.** The signed manifest ships inside the APK, and `init`
  takes `max(remembered, shipped)` — otherwise a first launch would start at serial 0 and accept any
  old validly-signed manifest, which is the launch an attacker would target.
- **Signing is local, never CI.** The release keystore has to be a repository secret; putting the
  schema key beside it would mean one account compromise yields both a malicious APK and malicious
  schemas. `verify-schemas.yml` only checks the committed result — see `BUILD_AND_RELEASE.md`.

### Model integrity: `sha256` beside the URL

A signed schema vouches for a URL, not for the bytes at it. So a model entry may declare a `sha256`,
which — because the schema itself is signed — inherits that signature's authority:

```json
{ "id": "base", "path": "https://…/ggml-base.bin", "sha256": "60ed5bc3…" }
```

`ModelDownloader` checks it at the one choke point every download passes through, before the artefact
reaches a native parser, and deletes what does not match. **Absent means unverified and stays
supported** — a download that worked yesterday must work today — though all 102 model URLs carry the
field. `./scripts/vox schemas hash-models [engine]` fills it in by fetching each model once.

`RemoteSchema` fetches each file from `<repo>/main/remote-schemas/<folder>/<file>` and compares it by
hash with the copy in force. A copy that differs *and still parses* is written to the app's `filesDir`
and becomes the source of truth until the user resets it — updating is something the user does, with
the bundled schema always available as the choice to return to (`SchemaUpdatesSection` in
`:core:design`). `validate-schemas.yml` validates the folder on any push that touches it.

### models.json

**Location**: `remote-schemas/commander/models.json` → copied to assets by `copyShippedSchemas`
(a `preBuild` dependency, and the one prep task `-PvoxSkipNativePrep` does *not* skip, because the
schema tests read the generated assets)

**Parsed by**: `RemoteModelRegistry` (`data/remote/RemoteModelRegistry.kt`)

**Contents**:
- `schema_version` — Integer, used to detect newer versions for hot-reload
- `prompts.standard_nlu` — The NLU system prompt sent to LLM interpreters (OpenAI, Local LLM). Contains sentence anatomy rules, domain/action taxonomy, and JSON output format
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

**Location**: `remote-schemas/commander/search_definitions.json` → copied to assets by `copyShippedSchemas`

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

**Location**: `remote-schemas/commander/intents.json` → copied to assets by `copyShippedSchemas`

**Parsed by**: `IntentCatalog` (`domain/intent/registry/IntentCatalog.kt`) — mirrors `SearchProviderRegistry` (init / fetchRemote / ensureLocalFile / loadFromFilesDir / saveLocalFile, schema-versioned no-downgrade).

**Purpose**: The catalog of standard Android intents probed per installed app. See §7 — `AppRegistry.probeSupported()`/`probeMetadata()` iterate this catalog.

**Contents**:
- `schema_version` — Integer, for hot-reload detection
- `template_action_domains` — Map of `templateAction` → domain (`navigate`→`maps`, `search`→`audio`, `send`→`messaging`)
- `taxonomy` — The NLU vocabulary (added in schema v2): `domains` (list), `actions` (flat list), and `actions_by_domain` (map). This is the domain/action list fed to the NLU prompt and the Rules UI. `IntentTaxonomy` keeps the domain/action *constants* (handlers dispatch on them) but reads these *lists* from here via `IntentCatalog`, with a single seed fallback in `IntentCatalog`. Adding a vertical (e.g. `notes`) to the LLM's vocabulary is a JSON edit; it routes via `GenericLaunchHandler` unless it needs a bespoke handler.
- `intents` — Array of intent definitions: `action` (the literal Android action string, e.g. `android.intent.action.VIEW`), `probe_uri`, `uri_template` (with `{query}`/`{destination}`/`{contact}` placeholders), `label`, `template_action`, `requires_query`, `mime_type`

`intents.json` is thus the **capability manifest**: what verticals exist (taxonomy) + what any app can be asked to do (intents).

**Hot-reload**: fetched from the remote repo at startup and via the Settings "Sync JSON" button, same mechanism as `models.json`/`search_definitions.json`. If the JSON is missing/unparseable, a compact hardcoded seed (core routing intents) keeps the app functional.

**Adding a probeable intent**: add an entry to `intents.json` — no code change. `IntentCatalogTest` is a golden test asserting each SDK action constant is transcribed byte-exact (a mistyped literal would silently never match).

### normalization.json

**Location**: `remote-schemas/commander/normalization.json` → copied to assets by `copyShippedSchemas`

**Parsed by**: `TextNormalizer` (`domain/voice/TextNormalizer.kt`) — reads the asset copy (`schemas/normalization.json`)

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

### virtual_models.json

**Location**: `remote-schemas/commander/virtual_models.json` → copied to assets by `copyShippedSchemas`

**Parsed by**: `RemoteModelRegistry` — merged under the same engine catalogue as `models.json`, so
one lookup answers for every engine regardless of which file declared it. `models.json` describes
what can be downloaded and run; `virtual_models.json` describes services that need no file.

**Contents** (`schema_version` 14) — the engines with no downloadable model:

| Engine key | Declaration |
|---|---|
| `GOOGLE` | Android's on-device speech recognition — `runtime: "android_local"`, capability `google_service` |
| `WHISPER_API` | OpenAI's hosted Whisper STT — `runtime: "cloud"`, capability `requires_api_key` |
| `OPENAI` | OpenAI GPT chat completions — `runtime: "cloud"`, capabilities `requires_api_key`, `multimodal` |
| `android` | The platform TTS — `runtime: "android_local"` |

The consent toggles (§11) gate these **by declaration, not by name**: `cloudIntelligenceEnabled`
covers whatever declares `runtime: "cloud"`, `googleServicesEnabled` whatever declares the
`google_service` capability — so an engine added to this file is gated correctly with no code change.

### media_services.json

**Location**: `remote-schemas/commander/media_services.json` → copied to assets by `copyShippedSchemas`

**Parsed by**: `MediaServiceRegistry` (`domain/media/MediaServiceRegistry.kt`)

**Contents** (`schema_version` 1) — the backends that can answer "play this video": `piped`, with its
interchangeable `endpoints` (one service, several public hosts, any of which can answer — and any of
which can go away; replacing one is a schema edit, not an app release), a `probe_url` for connection
tests, and the region list the settings picker offers; and `newpipe`, declared
`runtime: "device_builtin"` — a compiled-in library parsing YouTube on-device, with no endpoint a
schema could carry. `PipedSearchHelper` reads `MediaServiceRegistry.endpoints(...)` (§8).

### Build Integration

One `Copy` task ships every schema: `copyShippedSchemas` copies `remote-schemas/commander/*.json`
plus `remote-schemas/shared/*.json` — and the signed `manifest.json`, so a fresh install has its
rollback floor — into `vox-commander/src/main/assets/schemas/`. The folder is the list: nothing
names individual files, so adding a schema means dropping a JSON into the folder.

`preBuild` depends on exactly three tasks: `autoCompileWhisper` and `autoCompileLlama` (both skipped
by `-PvoxSkipNativePrep`, for verification builds that only need the Kotlin to compile) and
`copyShippedSchemas` (never skipped — the schema tests read the generated asset copies).

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
| llama.cpp | (submodule, CMake) | On-device LLM inference (GGUF, GBNF grammar sampling) |
| Picovoice Porcupine | 4.0.2 | Wake word engine |
| OpenWakeWord | v0.1.5 (rementia, vendored fork — `:core:wakeword`) | Wake word engine, RMS silence-gate patched |
| ONNX Runtime | 1.28.0 | ML inference for OpenWakeWord |
| Spotify App Remote | (local AAR) | Spotify media control |
| NewPipe Extractor | v0.26.4 (JitPack) | YouTube search/parsing |
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
| `autoCompileWhisper` | Checks whisper.cpp upstream and recompiles via CMake when stale |
| `autoCompileLlama` | Checks llama.cpp upstream and recompiles via CMake when stale |
| `autoCheckVosk` | Checks for a newer Vosk version on JitPack |
| `autoCheckNewPipeExtractor` | Checks for a newer NewPipeExtractor on JitPack |
| `autoCheckOpenWakeWord` | Checks for a newer OpenWakeWord upstream tag and whether the patches would still apply (see [§2 OpenWakeWord Fork & Sync](#openwakeword-fork--sync)) |
| `copyShippedSchemas` | Copies `remote-schemas/commander/*.json`, `remote-schemas/shared/*.json`, and the signed `manifest.json` into `src/main/assets/schemas/` |
| `checkUpstream` | Runs every upstream check at once (Vosk, NewPipe, OpenWakeWord, OpenCV, PaddleOCR, whisper) |

Only the two compile tasks (skipped by `-PvoxSkipNativePrep`) and `copyShippedSchemas`
(unconditional) are `preBuild` dependencies. The `autoCheck*` tasks and `checkUpstream` are
on-demand only — upstream movement is a maintenance fact delivered by the scheduled sync workflows
(§2), not a build fact.

### Repositories

- **Google Maven** — AndroidX, Compose
- **Maven Central** — OkHttp, Retrofit, Gson, Apache Commons, ONNX Runtime
- **JitPack** — Vosk, sherpa-onnx, NewPipe Extractor
- **Picovoice Maven** — Porcupine

OpenWakeWord is vendored as source (`:core:wakeword`, patched) rather than resolved from JitPack, with
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

A small Android library (`com.voxapps.ipc`), mostly the wire protocol (DTOs + constants, no logic),
compiled into each app (like `:core:design` for theming). A third-party developer can integrate by
mirroring these strings locally; nothing forces a dependency. One exception to "no logic": it also
hosts `VoxLlmRequestQueue` and the durable pending-request Room entity/DAO — see
[Durable delivery: the pending-request queue](#durable-delivery-the-pending-request-queue-voxllmrequestqueue) —
since that's genuinely shared behavior every satellite's outbound `ACTION_LLM_PROCESS` sends benefit
from, not per-app wire-format constants.

| Type | Purpose |
|------|---------|
| `VoxIpc` | Constants: actions (`ACTION_COMMAND`, `ACTION_SPEAK`, `ACTION_LLM_PROCESS`, `ACTION_LLM_RESULT`, `ACTION_OCR_RESULT`, `ACTION_SCHEMA_CHANGED`, `ACTION_CAPABILITY_QUERY`), extras, ops (`OP_CREATE`, `OP_READ`, `OP_PING`, `OP_GET_SCHEMA`, `OP_GET_FIELD_SCHEMA`, `OP_EXPORT`, `OP_IMPORT`, `OP_SYNC_EXPORT`, `OP_SYNC_MERGE`, `OP_MEDIA_CONTROL`), capability meta-data keys (`META_DOMAIN`, `META_ACTIONS`, `META_LABEL`, `META_NLU_HINT`, `META_OCR_TASK`), the six shared `com.voxapps.vox.permission.*` constants |
| `VoxCommand` | Command envelope authored by Commander (`op`, `text?`, `title?`, `category?`, `domain?`, `exportScope?`, `dateFrom?`, `dateTo?`, `since?`, `scopeNames?`) with `toJson()`/`fromJson()` (org.json) — `dateFrom`/`dateTo` are an additive pair used only by Vox Calendar's day-scoped `OP_READ` (see below); `since`/`scopeNames` back `OP_SYNC_EXPORT`/`OP_SYNC_MERGE` (see [Peer-to-peer device sync](#peer-to-peer-device-sync-op_sync_export--op_sync_merge) below); every other satellite's `OP_READ` ignores them and behaves exactly as before |
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
   Requires every first-party app to actually share one release signing certificate (see
   [Satellite App Guide §8.1](SATELLITE_APP_GUIDE.md#81-signature-level-permissions-are-the-entire-trust-mechanism))
   — every app must sign with the **same** alias (`vox-apps`) in the shared keystore, because two
   aliases in one keystore file are unrelated key pairs and this check then returns `false` between
   apps in release builds, silently.
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
- **`LlmHookReceiver`** does only fast parse/validate work — the request payload is staged to a
  cache file (WorkManager `Data` has a hard 10 KB cap that a large prompt exceeds), deleted only
  on a terminal outcome so an OS-stopped worker's reschedule can re-read it — then hands off to a
  one-time **`LlmHookWorker`** (`WorkManager`, not a plain `Service`) — on-device testing showed a plain
  non-foreground `Service` started from a `BroadcastReceiver` can be silently blocked by OEM/Doze
  background-execution restrictions when Commander has no visible UI, whereas `WorkManager`'s
  `JobScheduler`-backed execution is exempted.
- **`LlmHookEngineSelector.run(promptText)`** routes the raw prompt to whichever engine is currently
  configured as the user's primary AI processor (`aiProcessor` setting — the same selection
  [`IntentDecisionMap`](#4-natural-language-understanding-nlu) uses for its L2 step), calling
  `AssistantEngine.rawPrompt()` directly with **no L1/L3 fallback cascade** (this hook always targets a
  single, currently-selected engine, not the triple-brain pipeline). A cloud engine choice is
  refused when cloud intelligence consent is off. On an OpenAI failure it now
  surfaces the actual HTTP-code-derived reason (`OpenAiInterpreter.lastErrorReason` — bad/revoked key,
  rate limit, or a transient 5xx) instead of a hardcoded "check API key" for every failure.
- **`LocalLlmInterpreter` serializes every call** (`processCommand`/`rawPrompt`) through a `Mutex` —
  the interactive `processCommand` under a 90s timeout, `rawPrompt` under a 300s budget that covers
  both the mutex wait *and* the run (a hook call queued behind a long voice command would otherwise
  time out before its own work started; a burst of queued hooks fails fast as busy instead of
  stacking up forever). The `Mutex` is the interpreter's only synchronization: it's a process-wide
  singleton with a check-then-act `setupLlm()`, and without serialization a burst of concurrent
  callers (e.g. Expenses' "Force-check notifications now" forwarding several matched notifications
  at once) means N concurrent, memory-heavy model loads. A failed `rawPrompt` records
  `lastErrorReason` — engine busy (did not finish within the budget), model not available (not
  downloaded or failed to load), generation failed, or no local model selected — which
  `LlmHookEngineSelector` reports as `Local engine: <reason>`, symmetric to the OpenAI error path
  above.
- **The raw-reply budget is measured, not flat.** A raw-prompt completion reserves a
  2048-token output ceiling; when the prompt plus that reservation does not fit the per-sequence
  capacity, the native rejection carries the measured token counts and the call retries once with
  the exact remainder — below a minimum remainder the prompt has swallowed the context and the
  call fails honestly. Every number involved is a measured token count, never a character-based
  estimate.
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
satellite  ◀── VoxLlmResult{task,status,rawJson,input?} ── explicit intent, signature-checked ┘
```

### Durable delivery: the pending-request queue (`VoxLlmRequestQueue`)

The generic LLM hook above is fire-and-forget: a plain `sendBroadcast` with no delivery confirmation.
A real-world incident (a bank-notification capture in Vox Expenses sitting unprocessed for hours)
traced to two compounding causes, both now fixed:

- **Android's stopped-app broadcast gate.** Since Android 3.1, an app the OS considers "stopped"
  (force-stopped, or killed by an OEM background-management feature — this device family's
  "AppFastHibernation" is one observed example) does not receive **any** broadcast, implicit or
  explicit, permission-guarded or not — `setPackage()` targeting alone does not bypass this. Every
  sender of `ACTION_LLM_PROCESS` (and Commander's own `ACTION_LLM_RESULT` reply) now adds
  `Intent.FLAG_INCLUDE_STOPPED_PACKAGES`, which tells the OS to wake the target and deliver anyway.
- **No retry on top of that.** Even with the flag, Commander might not be installed yet, or could be
  killed again mid-processing. `VoxLlmRequestQueue` (`core/ipc/src/main/java/com/voxapps/ipc/`) adds
  durability: `enqueueAndSend()` persists a `PendingLlmRequestEntity` row (Room) **before** attempting
  delivery, so a dropped broadcast is recoverable instead of silently lost.
- **One pending row per request identity.** `enqueueAndSend()` dedupes on (source package, target
  package, base task): every capture path that can rediscover the same work — a listener reconnect,
  a manual force-check, a periodic sweep — funnels through here, and a re-enqueue of a task that
  already has a pending row re-sends the stored row and returns its existing `requestId` instead of
  minting a fresh one (each rediscovery would otherwise hold its own live row, independently
  processed and independently answered). Rows past the retry cap dedupe too — an explicit re-enqueue
  is exactly the manual retry that should reach a dormant row.

```
satellite: queue.enqueueAndSend(task, promptText, ...)
   │
   ├─▶ INSERT pending_llm_requests (requestId, payloadJson, attemptCount=1, ...)   [durable]
   │
   └─▶ sendBroadcast(ACTION_LLM_PROCESS, FLAG_INCLUDE_STOPPED_PACKAGES, task="...:<requestId>")
                                                                                          │
                                                              (Commander processes, replies)
                                                                                          │
satellite's LlmResultReceiver ◀── VoxLlmResult{task="...:<requestId>", status, rawJson} ──┘
   │
   ├─▶ VoxLlmRequestQueue.splitRequestId(result.task) → (originalTask, requestId)
   ├─▶ queue.markFulfilled(requestId)   — DELETE the pending row (success OR error reply — either
   │                                      is a definitive answer, not a delivery failure)
   └─▶ existing per-task-type handling, keyed on originalTask exactly as before

PendingLlmRequestRetryWorker (WorkManager, every 15 min — the platform's minimum periodic interval):
   for each row with lastAttemptAt older than 5 min and attemptCount < 50:
      increment attemptCount, re-dispatch with the same requestId
   (a row that exhausts 50 attempts — ~12.5h at this cadence — is left in place, not deleted, so
   it stays inspectable rather than silently vanishing a second time)
```

**The `requestId` tagging convention** is what makes this retrofit not require any change to
Commander: `enqueueAndSend()` appends a fresh UUID as a new trailing `:`-delimited segment on `task`
(the same encoding convention senders already used for other metadata, e.g. the notification-key/bank
segments in Vox Expenses' `PaymentNotificationListenerService`). Since `VoxLlmResult.task` already
round-trips its input verbatim — Commander never interprets `task`, only echoes it back — the
requestId comes back for free. `VoxLlmRequestQueue.splitRequestId()` peels that trailing segment off
(recognizing it by UUID shape; a task with no such segment is returned unchanged) before any existing
task-type dispatch logic runs, so per-app `LlmResultReceiver`s only need one new line
(`splitRequestId` + `markFulfilled`) at the top of their existing `when` block, not a rewrite.

**Not gated on Commander being installed.** `enqueueAndSend()` durably inserts and attempts dispatch
regardless of `VoxAppsDiscovery.isCommanderInstalled()` — a send to a genuinely uninstalled package is
a harmless no-op. This means "Commander installed later" self-heals for free via the next scheduled
retry pass, with no `PACKAGE_ADDED` listener needed.

**Per-app wiring** — each app's own `@Database` includes `PendingLlmRequestEntity::class` in its
`entities` list and exposes `abstract fun pendingLlmRequestDao(): PendingLlmRequestDao` (the entity/DAO
interface live once in `:core:ipc`; Room generates the DAO implementation wherever the concrete
`@Database` is compiled — a standard supported pattern for library-module entities, not a
cross-process shared table). Each app also registers `PendingLlmRequestScheduler.ensureScheduled(this)`
in its `Application.onCreate()`, alongside its other `WorkManager` schedules.

**Auto-accept is gated on identifiability** — on the notification-capture path, Expenses'
`LlmResultReceiver` auto-accepts a parsed reply (`autoAcceptNotificationExpenses`) only when it names
a title or a vendor (after `FieldCleaner` cleaning). A reply naming neither identifies nothing —
filing it would create an anonymous record the user can only puzzle over — so it goes to the
pending-review list instead, where approving it is a human call.

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
- **The reply carries the question back.** On this path — and only on this path — Commander is the
  only side that saw what the model was asked about: the satellite handed over a template and
  Commander filled it. `VoxLlmResult.input` is that text, echoed on success and on error alike (a
  failed answer is still an answer to something, and a satellite that only learned its own question
  on success would have to handle two shapes of one event). Without it, anything a rule on the device
  could have settled from those words is unreachable by the time there is an answer to check it
  against, so every field has to be taken from the model whether or not a rule could have answered
  it. It is deliberately **not** echoed on the generic hook (§19's `LlmHookWorker` path): there the
  satellite composed the request and its own durable queue still holds the input under the request id
  (`VoxLlmRequestQueue.originalInput`, read before `markFulfilled` deletes the row) — echoing would
  be sending a satellite its own words back, and on a scan those words are a whole page crossing a
  broadcast. Absence therefore means the same thing on both paths: "I did not compose this."
- **Satellite-initiated cache correction.** The one exception to manual-only refresh: if a satellite's
  own dynamic context changes as a side effect of normal use (e.g. Expenses auto-creating a category
  from a voice command, or a user editing categories in Expenses' own UI), the cached prompt template
  is now wrong. Rather than Commander guessing at the new state, the satellite pushes a corrected
  `VoxSatelliteSchema` via `VoxIpc.ACTION_SCHEMA_CHANGED` (fire-and-forget, the one
  satellite-initiated broadcast in this whole contract — everywhere else Commander initiates) the
  instant the mutation commits; `SchemaChangedReceiver` auto-applies it to the cache immediately. A
  precise, verified-event push, not a poll or timer.
- **KSP-generated field schema.** `ExpenseParsePromptBuilder`/`CalendarEventParsePromptBuilder`'s
  field-listing prose is generated rather than hand-typed, so it cannot drift out of sync with the
  actual parser (`ExpenseParseResultParser.Parsed`, etc). A `@VoxExtractionSchema(version)` annotation
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
vision tokens — OpenAI prices images by pixel-dimension tiling, not JPEG quality or color depth)
than OCR-extracted text. So OCR always runs; the photo, when enabled, is one more input on the same
single LLM call — never a second call.

- **Capability declaration.** `RemoteModelRegistry.isMultimodal(processor)` answers from the
  engine's declared capabilities — OpenAI is the one processor declaring `multimodal` today,
  and `hasCapability(engineKey, "multimodal")` covers JSON-defined local engines (none declare it
  yet). A new
  generic `VoxIpc.ACTION_CAPABILITY_QUERY` (ordered broadcast, `CapabilityQueryReceiver` on
  Commander's side, `VoxCapabilityClient.isMultimodal()` client-side) exposes this to any first-party
  app — deliberately separate from `VoxSatelliteSchema`/`OP_GET_SCHEMA`, since this is global Commander
  engine state, not per-satellite data.
- **Local-vs-remote declaration.** The same query also reports `RemoteModelRegistry.isLocalEngine(processor)`
  — answered from the engine's **declared schema `runtime`** (`local_file`/`android_local`/
  `device_builtin` → local; `cloud` → not; an unknown key reports false, because "I don't know what
  this engine is" must not read to the caller as "safe, it stays on the device"). The hardcoded cloud
  set (`Strings.AiProcessors.CLOUD_PROCESSORS`) survives only as `runtimeOf`'s inference fallback for
  schemas written before the `runtime` field existed — alongside `multimodal` in one round-trip
  (`VoxCapabilityClient.EngineCapabilities`). Callers use it to tune a prompt to the active engine's
  capability tier rather than assuming one: `VoxCapabilityClient.isLocalEngine()` fails safe to `true`
  on an inconclusive probe (the opposite direction from `isMultimodal()`'s fail-safe-`false`), since
  picking the more defensive, small-model-tuned prompt is the safer default when the probe can't tell.
  `NotificationExpenseParsePromptBuilder` (vox-expenses) is the worked example: its few-shot examples
  and anti-copy clause — added to work around a small local model's demonstrated tendency to leak
  literal example content into real output — are only included for a local engine; a cloud model
  doesn't share that failure mode and gets a shorter, example-free prompt instead of a padded-out copy
  of the local one.
- **Resolution, not quality, is what controls token cost.** Vision's Settings gets two new
  preferences: **"Send photo to AI"** (off by default — real token cost on top of free local OCR) and
  **"Photo detail for AI"** (Low/Medium/High → 768/1024/1536px long edge). Only resolution changes
  LLM token cost for an attached image; JPEG compression quality and color depth don't factor into
  OpenAI's tiling-based image tokenization, so neither is exposed as a
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
  engine is selected — `OpenAiInterpreter` attaches it (base64 data URI in the chat-completions
  format), every other engine implementation ignores the parameter.
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
  Routing it through a `BroadcastReceiver` that self-launches an Activity with no visible caller UI
  behind it is what BAL silently blocks, so no receiver sits in this path.
- **`VisionActivity`** is `android:launchMode="singleTask"` with an `onNewIntent` override, so a second
  scan request while it's already running redelivers into the same instance instead of losing the
  pending-request extras (both the launch mode *and* the override are required together — either alone
  is insufficient).
- Vision's OCR pipeline: camera capture → auto-capture → crop → on-device PaddleOCR. The crop quad
  comes from `DocumentCropper`'s priority chain — (1) the DocQuad ML corner detector
  (`vendor/docquad-sdk`, a port of MakeACopy's DocQuadNet-256), (2) the strict classical quad
  detector, (3) the looser blob heuristic — with OpenCV doing the warp/crop. Results are delivered
  to the requesting/selected app as `ACTION_OCR_RESULT`/`VoxOcrResult` by `OcrResultSender`;
  same-signature targets (Notes/Expenses/Calendar) are discovered by `ScanTargetDiscovery` via
  `queryBroadcastReceivers(ACTION_OCR_RESULT)` + `META_OCR_TASK` — Vision itself runs no LLM hook
  and does no forwarding-side cleanup. When launched as a pending-request target (`hint`/`task`
  present), an auto-triggered capture skips straight to submission with no manual tap — a manual
  capture always still requires one, since there's no guarantee every field is already correct.
- **Zone-based OCR models download at runtime**: `OcrModelRegistry` (`assets/ocr_models.json` — one
  universal detection model, per-zone recognition models and char-dict configs, each
  `{url, sha256}`) plus `VisionModelDownloader`; adding a zone is a JSON edit.
- **Table mode**: when `VoxOcrRequest.tableMode` is set, `TableReconstructor` appends a
  `--- [table reconstruction] ---` section (`OcrEngine.TABLE_SECTION_MARKER`) after the plain OCR
  text, and the plain text is line-broken at printed-row boundaries; OCR output always leaves in
  reading order, not detector order.
- **Stitch capture**: multi-shot stitching joins accepted shots' text with
  `ContinuityMatcher.STITCH_SEAM_MARKER` (`--- [photo stitch seam …] ---`) at each join.
- **Vox LiveView** — a second launcher entry on the same activity. The manifest declares an
  `activity-alias` (`.LiveView`, its own `MAIN`/`LAUNCHER` filter, label `Vox LiveView`);
  `VisionActivity` reads `intent.component.className` in `onCreate`/`onNewIntent` and shows
  `LiveViewScreen` instead of the scan flow — an alias can add no extras, so the component name *is*
  the mode. A pending OCR request always wins: satellites launch `.VisionActivity` by name and the
  payload check runs first, so the scan contract is unreachable from — and untouched by — LiveView.
  The two screens share only the camera floor (`ui/CameraFrames.kt`: analysis-interval floors,
  stability tolerances, the FIT_CENTER `remapForPreviewCrop`, frame conversions, the fixed sensor
  orientation, and the single `nativeCvLock` that serializes every native OpenCV entry point in the
  process). LiveView runs **stable-frame reads**: the document-bounds detector is the stability
  gate; once the framing holds, the upright color analysis frame goes through
  `OcrEngine.read(bitmap)` — the geometry-preserving sibling of `recognize()`, returning
  `RowClusterer`'s printed rows with their boxes — on the analysis thread under `nativeCvLock`.
  Rows are classified by `:core:textmatch`'s `LineEntities` (custom regex categories first, then
  checksum-gated account, email, URL, phone by evidence rungs, labeled street address; per-kind
  opt-in fuzzy tiers; generic otherwise), a generic line under an address folds in via
  `looksLikeAddressContinuation`, and national phone numbers are completed by `CountryDialing`
  (ccTLD → E.164 prefix, trunk-zero rules; site's domain first, email's second; stated
  international numbers never rewritten). Each row renders a float of chips anchored to its box:
  the kind's baked-in action through the system default, then the user's added apps (full
  installed-app list via `QUERY_ALL_PACKAGES`, same declaration and reason as Expenses/Commander),
  fired by `:core:design`'s `EntityActions` — `dial`/`composeEmail`/`openUrl`/`openMaps`/
  `searchWeb` plus the `*ToApp` family that tries an app's most specific carrier before falling
  back to shared text. Between reads the chips ride the document rectangle's affine map (pan
  translates, zoom scales); a finished reading is cleared only on sustained evidence — a miss-grace
  of detection-less ticks or a streak of foreign rectangles — at a user-chosen rescan eagerness.
  Three result styles (live chips / recognized text painted into filled boxes / a frozen frame with
  the fields as a table, specific kinds first), detector pace, per-kind exact-fuzzy strictness,
  float apps and custom categories are all settings; LiveView state lives in
  `VisionSettingsRepository` keys prefixed `liveview_`.
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
- **`VoxDataTransferClient.requestExport(context, pkg, scope, includeSecrets, includePhotos)`/
  `requestImport(context, pkg, json)`** send the same `OP_EXPORT`/`OP_IMPORT` ordered broadcasts a
  satellite's own `VoxCommandReceiver` already answers — Hub adds no new wire protocol, just a
  client-side helper. `ExportImportUtil.summarize()` is the one spot with a small hardcoded per-app
  JSON-key list (for the cosmetic "3 notes, 2 categories"-style import preview) — every other Hub code
  path is fully generic.
- Because Hub holds no Room database, its own settings (theme preference, backup schedule, per-app
  backup config below) are the only thing it persists locally.
- **Import modes.** A restore carries a `VoxCommand.importMode`, resolved by
  `VoxSnapshotReplaceImporter`'s `VoxImportMode`: `FULL_OVERRIDE` deletes every pre-existing item,
  `MERGE` deletes only pre-existing items with `createdAt <= exportedAt` (the default; null on the
  wire resolves to it), `ADDITIVE` deletes nothing. Every restore surface — Hub's and each app's
  own — states the selected mode's consequences right where the mode is chosen, and dateless to-do
  items ride the entries snapshot like every other row.

**Per-app backup configuration (`AppBackupConfig`)** — the main Export screen and `BackupWorker`
(the scheduled path) obey one persisted, per-package
`AppBackupConfig(includeSettings, includeData, includeApiKeys, includeAttachments)` (`vox-hub/.../domain/backup/AppBackupConfig.kt`), stored as a hand-rolled
`org.json`-encoded map in `HubSettings.appBackupConfigs` (matches Hub's existing `ExportImportUtil.kt`
convention — Hub has no Gson dependency). Both `includeSettings=false` and `includeData=false` for an
app means "skip it entirely," so there is no separate master toggle. An app not yet in the map (newly
installed) falls back to `AppBackupConfig.DEFAULT` via the `configFor(packageName)` extension
(scope BOTH, secrets off, photos off, every app
included).

This single config now drives **both** the manual Export button and `BackupWorker` identically, via
one shared `requestExportFor(context, app, config): VoxResult?`
(`vox-hub/.../domain/backup/BackupExportRequest.kt`): `includeSettings`+`includeData` both true →
`EXPORT_SCOPE_BOTH`, only one → that scope, neither → the app is skipped entirely (not called at all).
`includeApiKeys` maps to `includeSecrets` (only meaningful with Settings on — secrets live inside the
settings blob); `includeAttachments` maps to `includePhotos` (only meaningful with Data on) and also
scales the request timeout from 10s to 30s, since bundling attachment files is heavier than a plain
JSON export. The Hub main screen shows one card per discovered app with the four toggles (Data/
Attachments only shown for apps whose manifest advertises the `create` action, i.e. Commander is
Settings+API keys only) plus an **All** toggle that sets every toggle for every exportable app at once;
the Export button and the Scheduled Backups frequency/retention controls are both disabled when nothing
is selected. `HubSettingsScreen`'s Scheduled Backups section has no data-selection controls of its
own — it points back at the main screen's config ("This selection is also what scheduled backups
use").

**Multi-domain attachment zip bundling** — `BackupZipWriter.write()` takes
`attachmentZipEntries: Map<String, String>` (zip-entry name → content URI) end to end, built by the
shared `zipEntriesFor(domain, result): Map<String, String>`
(`BackupExportRequest.kt`), so every domain's attachment URI is carried rather than only Expenses'.
The Expenses receipts zip keeps the exact entry name
`"expenses-receipts.zip"` (from `VoxResult.attachmentUri`) so already-created backup files stay
restorable; every other domain's `:core:attachments` bundle is named `"$domain-attachments.zip"` (from
`attachmentUri` for domains with no legacy zip, or from the new **`VoxResult.secondaryAttachmentUri`**
field for Expenses specifically, since its primary `attachmentUri` field is already spoken for by
receipts). `BackupZipWriter` writes one entry per map entry, best-effort (a failed
`contentResolver.openInputStream()` for one entry logs a warning and skips just that entry, never fails
the whole export). Restore-side `HubScreen.readExportDocument()` mirrors this: it recognizes both the
exact `"expenses-receipts.zip"` name (→ domain `expenses`, import field `receiptsZipUri`) and the
`"$domain-attachments.zip"` pattern for any domain (→
import field `attachmentsZipUri`, a separate field name so it never collides with Expenses' existing
receipts field across independently-released app versions).

### Shared record attachments (`:core:attachments`)

A small Android library (`com.voxapps.attachments`) letting a user attach extra photos to an
already-created note/expense/calendar entry (beyond whatever photo, if any, created the record in the
first place) — consumed by `vox-notes`, `vox-expenses`, and `vox-calendar`.

- **No `@Database` of its own** — `AttachmentEntity`/`AttachmentDao` mirror `:core:ipc`'s
  `PendingLlmRequestEntity` pattern: each app's own encrypted Room database gets its own physical
  `attachments` table (via `@Database(entities = [..., AttachmentEntity::class])` in that app's own
  `AppDatabase`), rather than this module owning a shared database. `AttachmentEntity(id, recordType,
  recordId, fileName, source, createdAt)` — `recordType` is a per-app constant (`NotesAttachments.
  RECORD_TYPE`, etc.) rather than a foreign key, since attachments are looked up per-(app, record)
  pair, never joined across apps. `AttachmentFileStore` is plain file I/O (`filesDir/attachments/`),
  no Room involvement.
- **Reference-counted file delete** — `AttachmentDao.countByFileName(recordType, fileName)` exists
  specifically because Hub's "replace snapshot" import re-inserts an attachment row under a *freshly
  created* record id while **reusing the original on-disk fileName** (see attachments export/import
  below), ahead of deleting the *old* record it was originally attached to (the import's usual
  createdAt-filtered cleanup). Naively deleting a record's attachment files on every delete (as the
  first version of this cleanup did) would delete that shared file out from under the just-inserted
  row the moment the old record's cleanup ran — each app's `deleteAttachmentsFor(id)` now checks
  `countByFileName` and only deletes the physical file when no other row still references it.
- **`AttachmentsSection` (Compose UI)** — a collapsible card (thumbnail `LazyRow` + an "add" tile),
  shared byte-for-byte across all three apps. Starts collapsed only when there's nothing attached yet;
  this default is keyed on `items.isNotEmpty()` rather than an unkeyed `remember`, since the caller's
  list commonly starts empty for one frame before its DB flow's first emission — an unkeyed `remember`
  would lock in "collapsed" from that empty first frame and never reopen once the real, non-empty list
  arrives. Thumbnails use Telephoto's `ZoomableAsyncImage` with `ZoomSpec(maxZoomFactor = 1f)` (no
  pan/zoom — a thumbnail this small has nothing to zoom into) and tapping one opens a full-screen
  `Dialog` with default (zoomable) state and an X-close button. **Gotcha**: `Modifier.zoomable()`
  (used internally by `ZoomableAsyncImage`) consumes all gestures, so an *outer* `Modifier.clickable`
  around the thumbnail never receives the tap — the composable's own `onClick` parameter is the
  library's documented alternative and is what actually wires up the tap-to-zoom. The zoomed dialog
  sizes itself to the photo's real aspect ratio (a cheap `BitmapFactory.Options.inJustDecodeBounds`
  decode, not a full bitmap load, run once per opened URI) rather than a fixed square, and is capped to
  90% of both the available width *and* height via `BoxWithConstraints` — capping only width would let
  an extreme portrait/landscape photo push the corner close button outside the visible/touchable area.
- **Export/import** — each app's `*ExportImportHandler` gained an `AttachmentDao` constructor param;
  export nests an `"attachments"` JSON array *inside* each record's own JSON object (mirrors how
  Expenses already nests each expense's line `items`), and separately zips the referenced files (see
  the Hub subsection above for how that zip is bundled into the overall backup). On import, right
  after inserting a record and capturing its freshly-generated local id, the handler loops that
  record's nested `attachments` array and inserts an `AttachmentEntity` row per entry — no separate
  id-remapping table needed, since it reuses the exact same per-record insert loop import already does.
  **Each app's `res/xml/file_paths.xml` must declare a `<cache-path name="exports" path="exports/"/>`
  entry** (or reuse an existing one covering `cacheDir/exports/`) — `buildAttachmentsZip()` stages the
  zip there before handing Hub a `FileProvider` URI, and a missing path-config entry fails with
  `FileProvider`'s `IllegalArgumentException: Failed to find configured root` at export time, silently
  (best-effort catch) producing a backup with no attachments zip and no visible error unless
  logcat is inspected. Every app that exports attachments needs the entry; Vox Expenses' serves its
  receipts zip as well.

### Peer-to-peer device sync (`OP_SYNC_EXPORT` / `OP_SYNC_MERGE`)

A second, genuinely bidirectional path alongside export/import's one-directional "replace" restore —
syncs Notes/Calendar/Expenses between two phones over NFC + Bluetooth Classic, no cloud, entirely
orchestrated from Hub. Export/import and sync are deliberately separate wire ops rather than a mode
flag on the existing ones, since their semantics differ at every layer (snapshot-then-replace vs.
delta-then-merge).

- **Schema prerequisite** — every synced entity (`Expense`, `Note`, `CalendarEntry`) carries a stable
  `uid` (survives across devices, unlike the local Room auto-increment `id`) and an `updatedAt`
  timestamp bumped on every field-level edit, plus a small per-app tombstone table
  (`expense_tombstones`, `note_tombstones`, `calendar_entry_tombstones`) written on every delete —
  necessary because deletions need to propagate too, and a missing row is indistinguishable from a
  never-synced one without an explicit record of it.
- **`OP_SYNC_EXPORT`**(`since`, `scopeNames?`) — returns only entries with `updatedAt > since` plus
  tombstones with `deletedAt > since`, optionally filtered to `scopeNames` (category/layer names, by
  name rather than id since a local Room sequence has no cross-device meaning — mirrors how
  export/import's own category reconciliation already works).
- **`OP_SYNC_MERGE`**(`deltaJson`) — applies a peer's delta via `:core:datahygiene`'s shared
  `SyncIdentity`/`planMerge()`: insert-if-new, last-write-wins by `updatedAt` on a uid collision,
  delete-on-tombstone. Each satellite's `*SyncHandler.kt` (`ExpensesSyncHandler`, `NotesSyncHandler`,
  `CalendarSyncHandler`) wraps this with its own category/layer name resolution (auto-creating an
  unknown one, same convention as import).
- **NFC pairing** (`vox-hub/.../domain/sync/`) — `PairingHceService` (a `HostApduService`, the passive
  "card" side of a tap — Android wakes it automatically via AID routing, no foreground UI needed on
  that phone) and `NfcPairingReader` (`NfcAdapter.enableReaderMode`, the active side) exchange a
  `peerId` and a freshly generated AES-256 key over a tiny custom APDU protocol
  (`NfcPairingProtocol`). Deliberately **not** the Bluetooth MAC — Android forbids an app from reading
  its own adapter's address (a privacy restriction since API 23), so the client side instead resolves
  the server's MAC once via `BluetoothPeerResolver`: the server briefly requests discoverability and
  sets a temporary recognizable device name, the client runs a classic-Bluetooth discovery scan
  matching that name (reading a *remote* device's address from a scan result has no such
  restriction), then caches the resolved MAC — no repeat discovery, no OS-level bonding/PIN dialog,
  for any later sync with that peer.
- **Transport** (`BluetoothSyncTransport`, `SecureSyncChannel`, `SyncCrypto`) — an insecure RFCOMM
  socket (fixed app UUID, role fixed at pairing time: the HCE side always listens, the reader side
  always connects) carrying length-prefixed, AES-256-GCM-encrypted messages — the NFC-exchanged key is
  what actually secures the payload, standing in for the OS pairing this design skips.
- **`SyncOrchestrator`** drives one full session: both sides agree on which installed, syncable apps to
  cover, then for each one both sides call their own `OP_SYNC_EXPORT`, exchange deltas over the socket,
  and both apply the peer's via `OP_SYNC_MERGE` — genuinely bidirectional, not push or pull. Callable
  identically from a manual "Sync now" tap, or from `ScheduledSyncWorker` (a 15-minute `WorkManager`
  periodic tick — the platform's own floor — that checks each peer's own configured interval against
  `PairedPeer.lastAttemptedSyncAt` and only actually connects when due). A background worker can't
  prompt for anything, so it silently skips a peer whose Bluetooth isn't already on or whose runtime
  permissions aren't granted, retrying next tick — real reliability depends on both phones' independent
  `WorkManager` schedules happening to overlap, which is an inherent limitation of the platform, not
  something the app can paper over.
- **Scope selection** — `SyncScopeScreen` reuses the existing `OP_EXPORT` (scope=`data`) call purely to
  read each app's category/layer *names* for a per-peer checklist, rather than adding a dedicated
  lightweight IPC op for it (this screen is opened rarely, unlike the orchestrator's own
  watermark-bounded `OP_SYNC_EXPORT` calls). Selections are stored per peer
  (`PairedPeer.scopeNamesByApp`); an entry absent from that map means "sync everything," the same
  convention every satellite's own `OP_SYNC_EXPORT` handler already uses for a null `scopeNames`.

### Key classes

| Class | Path |
|-------|------|
| `VoxIpc` / `VoxCommand` / `VoxResult` | `core/ipc/src/main/java/com/voxapps/ipc/` |
| `VoxLlmRequest` / `VoxLlmResult` | `core/ipc/src/main/java/com/voxapps/ipc/` |
| `VoxOcrRequest` / `VoxOcrResult` | `core/ipc/src/main/java/com/voxapps/ipc/` |
| `VoxSatelliteSchema` | `core/ipc/src/main/java/com/voxapps/ipc/VoxSatelliteSchema.kt` |
| `VoxCapabilityClient` (multimodal + local-vs-remote engine capability query) | `core/ipc/src/main/java/com/voxapps/ipc/VoxCapabilityClient.kt` |
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
| Vision's result delivery + target discovery | `vox-vision/.../domain/OcrResultSender.kt`, `.../domain/ScanTargetDiscovery.kt` |
| Vision's table/stitch machinery | `vox-vision/.../ocr/TableReconstructor.kt`, `.../ocr/ContinuityMatcher.kt` |
| Vision's runtime OCR models | `vox-vision/.../OcrModelRegistry`, `.../VisionModelDownloader` |
| `VisionActivity` (`singleTask` + `onNewIntent`) | `vox-vision/src/main/java/com/voxapps/vision/VisionActivity.kt` |
| `DocumentCropper` (DocQuad ML corner detector first, then strict-quad, then blob heuristic) | `vox-vision/.../ocr/DocumentCropper.kt`, `vendor/docquad-sdk` |
| `downscaleToLongEdge` (AI-attachment photo resize) | `vox-vision/.../ui/VisionScreen.kt` |
| `MultimodalAttachmentResolver` (Expenses' scan/retry photo-attach gate) | `vox-expenses/.../domain/llm/MultimodalAttachmentResolver.kt` |
| Day-scoped read + ICS export/import | `vox-calendar/.../receiver/VoxCommandReceiver.kt`, `vox-calendar/.../domain/ics/` |
| Hub's export/import client | `core/ipc/.../VoxDataTransferClient.kt`, `vox-hub/.../ui/HubScreen.kt` |
| `AppBackupConfig` / `requestExportFor` / `zipEntriesFor` (per-app backup config, shared export call) | `vox-hub/.../domain/backup/AppBackupConfig.kt`, `BackupExportRequest.kt` |
| `BackupZipWriter` / `BackupWorker` (multi-domain zip bundling, scheduled backups) | `vox-hub/.../domain/backup/` |
| `AttachmentEntity` / `AttachmentDao` / `AttachmentFileStore` / `AttachmentsSection` (shared record attachments) | `core/attachments/src/main/java/com/voxapps/attachments/` |
| `SyncIdentity` / `planMerge()` (shared merge algorithm) | `core/datahygiene/.../SyncMerge.kt` |
| `RuleBasedDuplicateChecker` / `RuleField` / `DuplicateRule` (generic duplicate-rule engine) | `core/datahygiene/.../RuleBasedDuplicateChecker.kt` |
| `RecordProvenance` / `recordScore()` (generic merge-quality scoring) | `core/datahygiene/.../RecordScore.kt` |
| `ExpenseSource` / `Expense.dataScore()` (vox-expenses' wiring) | `vox-expenses/.../data/ExpenseDataScore.kt` |
| Per-app sync handlers | `vox-expenses/.../receiver/ExpensesSyncHandler.kt`, `vox-notes/.../receiver/NotesSyncHandler.kt`, `vox-calendar/.../receiver/CalendarSyncHandler.kt` |
| `PairedPeer` / `SyncPeerStore` (persisted per-peer identity + key) | `vox-hub/.../domain/sync/` |
| `PairingHceService` / `NfcPairingReader` / `NfcPairingProtocol` (NFC pairing) | `vox-hub/.../domain/sync/` |
| `BluetoothPeerResolver` / `BluetoothSyncTransport` / `SecureSyncChannel` / `SyncCrypto` | `vox-hub/.../domain/sync/` |
| `SyncOrchestrator` / `ScheduledSyncWorker` / `ScheduledSyncScheduler` | `vox-hub/.../domain/sync/` |
| `SyncScreen` / `SyncScopeScreen` | `vox-hub/.../ui/` |

---

## 20. Shared UI Modules (`:core:calendar`, `:core:apppicker`, `:core:design` color picker)

Three code-reuse-only Gradle modules (no shared runtime state, just library code compiled into
whichever apps need it) shipped alongside the Vox Apps ecosystem work: a calendar/agenda view, a
searchable app picker, and a color picker — each consumed by more than one app.

### `:core:design` shared components

Beyond the picker below, the module hosts the pieces more than one app needs to say the same thing the
same way:

- **`VoxSemanticColors`** — the colours that mean something rather than match something, deliberately
  kept out of the theme: done stays green and important stays amber whatever palette is chosen,
  because their job is to be recognised at a glance rather than to belong. `offered` and `asked` split
  the two kinds of proposal — green for a value the app believes, amber for one it is asking about —
  and must not look alike, since accepting an amber one teaches something that changes how later
  captures are read. `doneFill`/`doneOutline` are flat grey and flat black: a *tint* of an item's own
  colour says "still that item, quieter", so a list of finished items reads as a list of dimmed ones.
  What a border weighs is set by importance, never by doneness, so done changes colour and never
  weight.
- **`VoxFilterButton` / `VoxFilterSummary`** — one control for a narrowed list: it names what is in
  force, opens the filters, and clears them from a trailing ✕ shown only when there is something to
  clear. A filtered list otherwise looks exactly like a short one. `VoxFilterSummary.of(parts,
  whenNothingActive)` joins the active parts in the caller's fixed order, dropping nulls and blanks so
  a filter switched off does not move the ones beside it — a summary that reorders itself has to be
  re-read every time.
- **`VoxRangeBuckets`** — a few spans covering what the data actually holds, read from its smallest and
  largest values. Fixed brackets cannot work: the same list is somebody's weekly shopping and somebody
  else's rent, so 0–50 is every record for one and none for the other. Boundaries are rounded to the
  1/2/5/10-times-a-power-of-ten steps an axis is drawn with, and computed by index rather than
  accumulated — adding a step repeatedly drifts, and a boundary at 149.99999999 can leave a record in
  no bracket at all. Nothing to divide (one value, or several identical) yields no brackets, since one
  bracket holding everything is not a filter.
- **`VoxIcons` / `VoxIconPickerDialog`** — a short piece of text standing for a category or an account,
  offered as a grid and as a free-text field, because a fixed set can only ever be someone else's idea
  of what people record. Text rather than a drawable reference, so it survives into a widget that
  renders no vectors and into a backup restored on another device.
- **`VoxCategoryFields`** — name, colour and icon as one body two containers hold: the settings card
  that manages categories and the dialog reached while filing a record that has none yet. Each had its
  own copy, and copies drift — a slot added to one goes missing from the other, so which screen you
  happened to use decided what your category could carry.

### `:core:design` color picker

Vox Notes, Vox Expenses, and Vox Calendar each independently grew their own category/layer color
picker (a hardcoded 10-preset list + hue-distance math + a swatch row), triplicated almost
byte-for-byte. Consolidated into `core/design/src/main/java/com/voxapps/design/color/`:

- **`VoxColorPalette`** (pure Kotlin, no Compose dependency) — `presets: List<Long>` generated at
  evenly-spaced hues (`360° / presetCount`) rather than hand-picked hex values, so every pair is
  guaranteed a minimum hue separation instead of relying on named colors that can drift close together
  despite looking distinct by name (the previous hand-picked list had three presets within 15° of each
  other). `unusedOrRandomColor(existingColors, precedingColor?)` — the first unused preset (preferring
  whichever is farthest in hue from an optional `precedingColor`, e.g. the most-recently-added sibling
  category), or once presets are exhausted, a freshly generated hue biased to stay far from both the
  aggregate of existing colors and `precedingColor` specifically.
- **`VoxColorSwatchPicker`** (Compose) — a `LazyRow` of preset swatches with a genuinely clear
  selection indicator (an outer ring with visible padding around an inset solid circle, not a border
  drawn on the swatch's own edge) plus a trailing "Custom…" entry.
- **`VoxCustomColorDialog`** (Compose) — a full-screen `Dialog` opened from "Custom…": a live preview,
  the same preset row for a quick-pick shortcut (tapping one seeds the sliders from that color), then
  Hue/Saturation/Value sliders. Every label defaults to English but accepts caller-supplied strings
  (mirrors `rememberRequirementGate`'s `requiredMessage` param convention) so each app can localize via
  its own `LocalLanguageManager` — no shared translations file exists.

Each app's own `*Palette.kt` (`CategoryPalette` in Notes/Expenses, `CalendarLayerPalette` in Calendar)
is now a thin delegating wrapper over `VoxColorPalette`, and each app's own `*Colors.kt`
(`CategoryColors`/`LayerColors`) still owns `toStored`/`fromStored` (called from many non-picker
render sites) but derives `palette` from `VoxColorPalette.presets` — kept as separate per-app objects
rather than inlining every call site, so existing references didn't need touching.

### `:core:calendar`

A month-paged, per-day agenda view (`CalendarView.kt`) built on Compose's `HorizontalPager` — no custom
paging/fling physics needed, the pager provides deceleration for free. Consumed by `vox-notes` and
`vox-expenses` as an opt-in "Calendar view" setting (off by default), replacing their chronological
`LazyColumn` list, and by `vox-calendar` itself. The pager is anchored on the **selected** month —
`CalendarView` seeds it from the selected date, not today, and its month-swipe sync skips its first
run — so a recomposition or an editor round-trip keeps the user's month. Beyond the agenda view the
module also hosts the month-grid and hybrid display modes (`MonthGridView`, `HybridMonthView`,
`CalendarPagerState`, `NowClock`).

- **`CalendarItem`** — the only thing the module knows about a caller's data: `id: Any` +
  `dateTimeMillis: Long`. Each app wraps its own Room-backed model in a `@JvmInline value class`
  (`NoteCalendarItem`, `ExpenseCalendarItem`) implementing this interface — the module never depends on
  either app's data classes.
- **`CalendarDateUtils.dayToLandOn(month)`** — the day a month answers with when it becomes the one on
  screen: its first day, except the month containing today, which answers with today. Arriving in a
  month has to land somewhere, and the day-of-month carried over from the month just left is a date
  nobody chose — an artefact of where the last month happened to be standing, and it decides what the
  agenda below shows. Both display modes read this one function. In `HybridMonthView` the grid and the
  pager move each other, so the month on screen can change with nothing having touched the selection;
  the effect that corrects this is guarded on the selection already being in the displayed month,
  which is what keeps it from fighting the opposite sync — choosing a day outside the visible month
  scrolls the grid first, and by the time the guard runs the two already agree.
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

- **`AppPickerOrder`** — the order the list is offered in: starred first, then chosen, then the rest,
  each group case-insensitively alphabetical with the package name as a tie-break so the order is
  total and never depends on how the list arrived. Installed alphabetical order is the order of a list
  nobody has an opinion about, and these lists are long; a star outranks a tick because it says more.
  The order is computed when the list opens rather than as it is used — a row that leaps to the top the
  moment it is ticked takes the row underneath it, the one you were about to tick, along with it.
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
discards to `null` if the value is blank, **exactly** the literal string `"null"` (case-insensitive,
whole-string match — a sentence merely containing the word "null", e.g. "Null Island", is left alone),
or pure punctuation/whitespace with no letters or digits; `cleanRequired(value, fallback, ...)` is the
same predicate for a non-nullable field, coercing to `fallback` instead of `null`; `isDirty(value)` is
true only when a field has real garbage content that `clean` would discard — not just because it's
blank — so a manual-UI confirm dialog (below) never fires on a routine empty field. `dirtyValue(value)`
returns the actual offending trimmed text (or `null` if the field is fine) — this is what lets a
confirm dialog show the specific value that's wrong instead of a generic message.

**`optCleanString`** — a `JSONObject` extension (`optCleanString(key, fieldName, recordLabel)`)
delegating to `FieldCleaner.clean`, replacing every satellite's own ad-hoc null-string guard at the
JSON-parsing boundary.

**`RecordSanitizer<T>`** — the per-entity contract (`sanitize(record): T`,
`dirtyFields(record): List<DirtyField>`) each satellite implements once per data class it wants
covered (`ExpenseSanitizer`, `CalendarEntrySanitizer`, `NoteSanitizer`). `DirtyField(fieldKey, value)`
pairs a field identifier with its actual offending text. **`RecordSource`** (`LLM` / `HUB_IMPORT` /
`MANUAL_UI`) and the shared **`decideForSave(record, source)`** extension function encode the actual
policy so it's never reimplemented per app:

| `RecordSource` | Behavior |
|---|---|
| `LLM` | Always sanitized, always proceeds — no human in the loop to ask. |
| `HUB_IMPORT` | Always proceeds untouched — another install's already-validated data; rewriting it on import would be its own bug. |
| `MANUAL_UI` | Proceeds untouched if clean; if `dirtyFields` is non-empty, returns `SaveDecision.ConfirmCleanup` (carrying that list) instead of saving, so the calling screen can show "auto-clean or cancel and fix it yourself" before committing. |

Gating always happens in the **caller** (the LLM-result receiver, or the edit screen's Save handler),
never inside the shared repository's `add*`/`update*` methods — those are called by both manual saves
and Hub import with no way to distinguish the two internally, so sanitizing there would incorrectly
rewrite imported data too. Each app's existing inline `?.trim()?.takeIf { it.isNotEmpty() }` cleanup
inside the repository stays as-is and is unrelated to this stricter guard.

Each app's manual-UI confirm dialog renders the `ConfirmCleanup.dirtyFields` list directly — one line
per dirty field, the field's localized label followed by the actual offending raw text (e.g. `null`
or `.`) highlighted in red via a Compose `AnnotatedString`/`SpanStyle`, rather than a single generic
"some fields need cleanup" message — so the user sees exactly what's wrong before deciding.

See the [Satellite App Guide §6.6](SATELLITE_APP_GUIDE.md#66-data-hygiene-cleaning-records-before-insert)
for the wiring pattern with code examples.

### Re-map rules engine (`RemapEngine`)

`core/datahygiene/.../RemapEngine.kt` — user-taught WHEN/THEN re-map rules: WHEN the rule's match
fields all equal its stored normalized values, THEN its set fields are written. The app injects
`RemapMatchField`/`RemapSetField` descriptors (same convention as `RuleField`); matching is
trim+case-normalized exact unless a match entry carries a fuzz level resolved by an injected
matcher; a setter may decline (null). Precedence is a total order — more match fields first, then
user `sortOrder`, then id — winning per set-field (the first matching rule to set a field owns it),
and rules never chain: every rule matches the pre-remap snapshot. `vox-expenses` wires it through
`data/ExpenseRemapFields.kt`, with rule proposals generated from repeated user edits and confirmed
before they apply.

### Field-correction memory (`:core:fieldmemory`)

`FieldCorrectionMemory` + `LearnedFieldCorrection` (Room) learn one-word spelling fixes from
old→new manual-edit pairs via `:core:textmatch`'s `FieldCorrections.diff`. Identity is
`VocabularyClassifier.termKey` (case/punctuation variants collapse; the first-seen spelling is
kept); a genuinely different second fix quarantines the garbled word permanently; corrections
activate at a consecutive-count threshold (`activeCorrections(threshold)`).

### Deterministic extraction (`:core:textmatch`)

Beyond `FuzzyNameMatcher`, the module hosts a deterministic-extraction package (`extract/`):
`TemplateSkeleton` (a notification's byte-shape identity), `DateTimeExtractor`,
`LabelledAmountExtractor`, `VocabularyClassifier`, `TwoFieldPreParse`, `AccountIdentifiers`,
`FieldCorrections`, `AmountText`, `Findings`.
Extraction is shared and deterministic; policy — what to do with a finding — stays per satellite.

`CurrencyMarkedAmounts` is why a two-line payment message yields an amount at all: a figure counts
only when a currency marks it, and only when exactly one distinct such figure is present. A
purchase-plus-balance notification carries two, and choosing between them is not a regex's call — so
it declines and the question goes to a model, or to a person.

`AccountIdentifiers` is the one extractor that needs no vocabulary at all, which is what separates it
from `VocabularyClassifier`: a bank's *name* is a fact about the world and has to be learned, while a
bank's *account number* is a fact about the string. It reads an IBAN (ISO 7064 mod-97), a full card
number (Luhn) and a masked tail (`••4535`, `**00`, `xxxx1234`, `...1234`), returning `AccountRef(kind,
digits)` most-specific-first with weaker readings absorbed by stronger ones that account for them. Two
refs are the same card when one's digits end with the other's — which is what lets two digits from a
notification meet sixteen from a receipt — while IBANs must match exactly and never match a card.
Both checksums are enforced rather than trusting length: a receipt is full of digit runs that are
sixteen long by coincidence, and the check digit is what separates an account from one of them. Two
format details earn their comments in the source — the IBAN pattern treats spaced-in-fours and
unspaced as *alternatives* rather than one lenient pattern (a lenient one reads the prose after the
number as more of the number, and the checksum then fails on a good IBAN), and a masked tail needs two
or more dots where every other mask character counts on its own, since a single dot is a decimal
separator and `12.34` would otherwise read as a card ending 34 on every receipt line.

### One payment announced twice (`SecondNotice`)

A duplicate check asks whether a record is already present. This asks something narrower and earlier:
whether a capture is a *second announcement* of a payment already filed — a reserved-then-settled pair
from one bank, arriving minutes apart about one purchase. `foldSecondNotice` folds it into the record
that exists instead of inserting, so the pair never reaches the duplicate engine as two rows to be
reconciled afterwards.

The conditions are deliberately narrow, because folding is not reversible: both sides captured from a
notification, the same currency, an amount equal to the cent, inside a three-minute window, and the
existing record not hand-edited — a record somebody has touched is one whose shape they chose, and
merging into it would overwrite that choice.

### Generic duplicate-rule engine

The same module also ships `RuleBasedDuplicateChecker<T>` — a `DuplicateChecker<T>` implementation
modeled on email-client filters rather than a hardcoded field list: an app registers a `RuleField<T>`
per comparable field (`id`, localization key, a `(candidate, existing) -> Boolean` matcher), the user
builds named `DuplicateRule`s (a set of field ids combined with their own `RuleCombinator.AND`/`OR`),
and however many rules exist combine via one global `AND`/`OR`. Evaluation: within each rule, its
selected fields combine via that rule's own combinator; the rules combine via the global one; a rule
whose fields don't resolve (an empty selection, or ids not present in the registered field list) never
matches rather than throwing — the UI is responsible for blocking an empty-field rule from being saved.
Three reusable field builders cover the common comparator shapes so a new entity's registry is mostly
one-line declarations: `stringField` (`FieldCleaner`-normalized, null-on-either-side never matches,
optional pluggable `FuzzyMatcher`), `exactField` (null-safe `==`), `timeWindowField`
(`abs(delta) <= windowMillis`).

`TemplateVerdictMemory` is the second of `:core:fieldmemory`'s two memories, and the one with the
strictest rule: a verdict about a message *shape* rather than a field value. Two unanimous human
confirmations before it answers on its own, permanent quarantine on the first disagreement — because
a shape that means one thing today and another tomorrow is a shape nothing should be filed on. The
verdict is an opaque string, so the app that owns the meaning maps it; this module only counts.

`:core:datahygiene` has no Room/entity knowledge of its own — the field registry, where rules persist,
and the rule-editing UI are each app's own responsibility. **vox-expenses** is the only current
consumer: `ExpenseRuleFields` (the field registry — title/vendor/bank/location/comments as fuzzy-eligible
string fields, totalAmount/currencyCode/categoryId/direction as exact fields, dateTime as a time-window
field), `DuplicateRuleEntity`/`DuplicateRuleDao` (Room storage, `fieldIds: List<String>` via a
comma-joined `TypeConverter`; migrated in with two default rules seeded — "same amount+currency+
direction+time, plus title OR vendor" — reproducing the app's prior hardcoded behavior as two AND-rules
OR'd together, both on upgrade and on a fresh install's `onCreate` callback), and
`ui/settings/DuplicateRulesSection.kt` (rule list + edit sheet: field `FilterChip`s, per-rule AND/OR,
per-rule fuzzy-vs-exact toggle, per-rule "applies automatically at save vs. review-only" toggle).

vox-expenses' `ExpensesRepository.buildDuplicateChecker(config, automaticOnly)` fetches the enabled
rules (optionally filtered to `appliesAutomatically` ones only), builds the field registry once, and
evaluates each rule against its own fuzzy setting via a single-rule checker per rule, then ORs/ANDs all
rule results per the stored global combinator. Exact and near duplicates are one pass — see the [Vox Expenses feature list](APPS_OVERVIEW.md#vox-expenses) for the user-facing
behavior, including the automatic-protection Off/Local/Local+AI/AI modes and the "Manual review" toggle
that stages an insert-time local-rule match into the same pending-review list `findLocalDuplicateGroups`
already uses for the on-demand/scheduled check, instead of merging it silently.

**`ruleBasedCandidateClusters(config, automaticOnly, scopedToId)`** — `MODE_LOCAL_AND_AI`'s recall pass
(both automatic and manual/scheduled). Clusters expenses using the same `buildDuplicateChecker` engine
`MODE_LOCAL` uses, so the AI-scoped confirmation check only ever fires for candidates the configured
rules actually flagged, respecting the time window and field choices. Pure `MODE_AI` has no local
component and keeps the older `duplicateCandidateClusters` (fixed amount/currency/direction grouping,
no rule consultation at all) — this asymmetry is intentional, not a gap: `MODE_AI` is meant to bypass
the rule engine entirely.

**Direction is an unconditional guard, not an opt-in rule field.** `buildDuplicateChecker` rejects a
candidate/existing pair outright whenever `direction` differs, before even consulting the configured
rules — confirmed on-device: a rule that only checked amount+currency let a 1000 RON top-up (incoming)
and a 1000 RON payment (outgoing) merge, since neither is a duplicate of the other regardless of what
fields a rule happens to select. The same guard exists in two more places for the AI path: `ExpenseSummary`
(the record sent to the LLM) now carries `direction`, and the prompt (`ExpenseDeduplicationPromptBuilder`)
explicitly tags each entry `(incoming)`/`(outgoing)` and instructs the model to never cross-group them;
`LlmResultReceiver.validateDuplicateGroups` (the anti-hallucination re-check on the AI's proposed groups)
now also verifies `direction` matches, not just amount/currency.

### Data-quality scoring for merges (`recordScore` / `Expense.dataScore`)

Both the silent insert-time merge (`enrichWithNearDuplicate`) and the review-approved merge
(`ExpensesRepository.applyExpenseDeduplication`) keep the *best* field content rather than whichever
arrived first. `:core:datahygiene` ships the generic building block for this, mirroring how
contact-merge tools (Google/Apple Contacts) and CRM dedup actually rank duplicate
records:

- **`RecordProvenance`** (`core/datahygiene/.../RecordScore.kt`) — an interface with one member,
  `trustTier: Int`. Distinct from `:core:datahygiene`'s existing `RecordSource` (`LLM`/`HUB_IMPORT`/
  `MANUAL_UI`, used by `decideForSave` to route a save through the sanitize-or-confirm policy) — a
  different concept (data trustworthiness vs. save routing) that happens to share the "where did this
  come from" framing, deliberately named differently to avoid confusion.
- **`recordScore(manuallyEdited, provenance, completenessFields)`** — `10_000` if `manuallyEdited`
  (an unconditional pin — a human touching a record always outranks everything else), plus
  `provenance.trustTier`, plus a count of non-null entries in `completenessFields` (the caller decides
  which fields count, mirroring `RuleField`'s "app hand-writes its own field list" convention — this
  module has no reflection-based field enumeration anywhere).

**vox-expenses' wiring** (`data/ExpenseDataScore.kt`): `ExpenseSource` (`MANUAL(400)`, `SCAN(300)`,
`NOTIFICATION(200)`, `VOICE(100)`) implements `RecordProvenance`; `Expense.dataScore()` calls
`recordScore(manuallyEdited, source, listOf(title, vendor, bank, location, comments, categoryId))`.
`Expense` gained `source`/`manuallyEdited` columns (migration v12→v13, existing rows backfill to
`MANUAL`/`false`). `source` is set at every creation path (`MANUAL` for the manual edit screen,
`VOICE`/`SCAN` for `LlmResultReceiver.createExpenseFromParsed` — already distinguished via
`imageName != null`, `NOTIFICATION` for the notification-capture paths, preserved from the original
device's JSON on Hub import). `manuallyEdited` is set only by `ExpensesStateManager.updateExpense` (the
genuine manual-edit-screen Save path) via `ExpensesRepository.updateExpense`'s `markManuallyEdited`
param — never by an LLM-driven rewrite (e.g. the scan "retry cleanup" path), and it's sticky (`||`'d
with the existing value, never reset back to `false`).

**`enrichWithNearDuplicate(existing, candidate)`** now compares `dataScore()` and lets the higher
scorer's non-null field values win (falling back to the lower scorer's), instead of always preferring
`existing`. Row identity (`id`/`uid`/`createdAt`) always stays `existing`'s regardless of which side's
*content* wins — `.copy()` is always called on `existing`, never `candidate` — which is what makes it
safe to `fold` across more than two records.

**`applyExpenseDeduplication`** now folds `enrichWithNearDuplicate` across every discarded group member
before deleting them, backfilling the kept row's blank fields from its higher-scoring duplicates, so
approving a review group keeps what the non-kept rows had rather than discarding it. A receipt image
adopted into the surviving row this way is excluded from the subsequent file-delete pass (tracked via
an `adoptedImageNames` set), so the merge can't delete a file the kept row now references.

**Review UI** (`ExpenseCleanupSettingsTab.kt`): the keep-picker defaults to whichever group member
has the highest `dataScore()`, rather than the detector/AI's anchor id, and stays overridable via the
per-member radio buttons. `expensePreview()` shows each
candidate's capture-source tag alongside total/bank/vendor/date-time, so the default is explainable
rather than a black box. The whole tab was also restructured from one large enclosing `Card` (which
made it visually stand out from every other Settings tab as a solid tonal block) into separate Cards
per section — Automatic protection (trigger), Duplicate rules (rules manager), Manual check (manual
trigger), Schedule — matching the plain-background-plus-per-item-card convention every other tab uses.

---

## 22. Project Structure

```
vox-commander/src/main/java/com/voxapps/commander/
├── MainActivity.kt              # Entry point, permission handling
├── VoxApplication.kt            # Application class, memory management
├── data/
│   ├── preferences/             # AppSettings, SettingsRepository, DataStore
│   ├── remote/                  # RemoteModelRegistry (models.json, engine defs)
│   └── local/                   # Room database, DAOs
├── di/
│   └── AppContainer.kt          # Dependency injection, startup wiring
├── domain/
│   ├── engine/                  # STT/TTS engines (Whisper, Vosk, Piper, Android TTS)
│   ├── intent/
│   │   ├── IntentDecisionMap.kt # Triple AI Brain orchestrator
│   │   ├── interpreter/         # OpenAI, Local LLM (llama.cpp), FastMap
│   │   ├── model/NluIntent.kt   # Universal intent data class
│   │   ├── taxonomy/            # IntentTaxonomy (domains, actions)
│   │   ├── registry/AppRegistry # App scanning, URI templates
│   │   ├── registry/ApiIntegrationRegistry # Declarative per-service API defs (api_integrations.json)
│   │   ├── resolver/AppResolver # App resolution with aliases + defaults
│   │   ├── router/IntentRouter  # Central dispatcher
│   │   └── handler/             # Audio, Navigation, System, Messaging, Search
│   ├── voice/                   # VoiceManager, WakeWordCalibrator, TtsManager
│   ├── search/                  # Search providers (web, news, weather, knowledge)
│   └── localization/            # LanguageManager (i18n)
├── service/
│   ├── WakeWordService.kt       # Foreground service, always-on listening
│   ├── WakeWordEngine.kt        # Vosk wake word with template + voice print
│   ├── SpotifyRemoteManager.kt  # Spotify App Remote SDK wrapper (out-of-band, separate from below)
│   ├── OAuth2Manager.kt         # Generic per-service OAuth2 (PKCE/auth-code) client
│   ├── DeclarativeApiExecutor.kt # Generic declarative-REST executor (api_call/api_sequence/deep_link)
│   └── MediaSessionListenerService.kt
├── state/
│   ├── AppState.kt              # Global UI state
│   └── AppStateManager.kt       # State flow management
├── ui/
│   ├── components/              # Reusable Compose components
│   ├── screens/
│   │   ├── main/                # Listening screen, voice overlay
│   │   ├── settings/            # settings menu + its subpages (§12)
│   │   ├── splash/               # Splash screen
│   │   └── rules/               # FastMap rule editor
│   └── theme/                   # Material 3 theme
└── utils/                       # Logger, NetworkMonitor, IntentUtils, etc.
```

Satellite apps (`vox-notes`, `vox-vision`, `vox-expenses`, `vox-calendar`, `vox-hub`) each follow the
same rough shape (`data/`, `di/`, `domain/`, `receiver/` for the `:core:ipc` contract, `state/`, `ui/`)
at a smaller scale — see [`SATELLITE_APP_GUIDE.md`](SATELLITE_APP_GUIDE.md) for the full convention a
new satellite app is expected to follow.

### Shared modules

Twenty-six `:core:*` modules, plus three vendored modules compiled in-tree:

```
:core:apppicker      :core:attachments   :core:audio        :core:backup
:core:calendar       :core:datahygiene   :core:design       :core:docread
:core:fieldmemory    :core:identity      :core:ipc          :core:location
:core:logging        :core:nativelibs    :core:onboarding   :core:preferences
:core:recordflow     :core:schema-annotations
:core:schema-processor  :core:services   :core:suggestions  :core:testing
:core:textmatch      :core:voxconnect    :core:wakeword     :core:widget

vendor/ppocr-sdk     PaddleOCR fork (+ 4 patches), compiled into vox-vision
vendor/docquad-sdk   DocQuadNet-256 corner detector (from-scratch port of MakeACopy files, see its
                     NOTICE — not a patch-tracked fork), compiled into vox-vision
core/wakeword        OpenWakeWord fork (+ 3 patches), compiled into vox-commander
```

The ones this documentation leans on most: `:core:design` (theme, the picklist family, the colour
picker, shared settings sections), `:core:services` (`ServiceEntry`, `ProbeSpec`/`ServiceProbe`,
`SchemaRepo`/`RemoteSchema`), `:core:ipc` (the cross-app plugin bus — §19), `:core:backup`
(export/import, biometric gate, snapshot replace), and `:core:datahygiene` (§21).

A vendored fork is upstream **plus** the diffs in its `patches/` folder, and that is checked rather
than assumed — `./scripts/vox patches verify` (scripts/verify_vendored_patches.sh), run in CI by `verify-vendor-patches.yml`. See
[`BUILD_TIME_DEPENDENCIES.md`](BUILD_TIME_DEPENDENCIES.md) for why, and what it caught.

---

## 23. Release Process & CI Automation

Each of the six apps has its own independent release pipeline — a `.github/workflows/release-<app>.yml`
per app (`release-commander.yml`, `release-calendar.yml`, `release-expenses.yml`, `release-notes.yml`,
`release-vision.yml`, `release-hub.yml`), all following the same shape.

### Verification on every push (`ci.yml`)

Before any of this: every push to `main` and every pull request runs `.github/workflows/ci.yml` —
`./gradlew test` across all modules, then `assembleDebug` for all six apps. Release workflows only
wake when an app's own `build.gradle.kts` changes, so without this a commit touching shared `core/`
code and no app's build file would be compiled and tested by nothing until the next release.

CI runs with `-PvoxSkipNativePrep` (drops Commander's whisper.cpp and llama.cpp compiles — the
upstream checks are on-demand tasks and never run in a build, see §18) and asks for 6 GB of heap,
because dexing six apps in one invocation ran D8 out of memory on `vox-vision`. The OpenCV build is
cached, keyed on the pinned `vendor/opencv` commit plus its build script; that one cannot be skipped,
since `org.opencv.*` comes from it and two modules import it. After `assembleDebug`, `ci.yml` also
compiles the instrumented-test sources (`compileDebugAndroidTestSources`) — those tests only *run* on
a device, but compiling them on every push is what catches ordinary drift between pushes.

Two narrower checks run on matching paths: `validate-schemas.yml` and `verify-vendor-patches.yml`
(see [§17](#17-dynamic-json-configuration) and `BUILD_TIME_DEPENDENCIES.md`).

### Weekly device run (`instrumented-tests.yml`)

The only tests that can catch a native-linking regression run on a device;
`.github/workflows/instrumented-tests.yml` runs Commander's instrumented suite weekly (plus manual
dispatch) — weekly rather than per-push because the native surface changes rarely and `ci.yml`
already compiles these sources on every push. It runs on Ubuntu with KVM enabled (GitHub's hosted
arm64 macOS runners cannot nest a VM, so an arm64 AVD never boots there): an x86_64 Android 11
`google_apis` image executes the arm64-v8a libraries through the system image's ARM binary
translation. The job builds `libllama.so` first (`./scripts/vox native llama` — the llama build is
hybrid CPU+OpenCL; its GPU inputs are repo-pinned, the `vendor/OpenCL-Headers` submodule plus the
in-repo dlopen import shim, so the job checks out that submodule and installs no host shader
packages beyond NDK+CMake), because `LlamaBridgeSmokeTest` is what answers whether
the compiled runtime actually executes. Commander only: translation has a fidelity ceiling
(vision's OpenCV load crashes the translated process while passing on real arm64), and
`NativeCrashReproductionTest` is excluded — its tests bring the process down on purpose and are run
one at a time by hand.

### Commander's release gates (`release-commander.yml`)

Beyond the shared release shape above, Commander's workflow refuses to publish an APK whose DLC
story cannot hold:

- **In-APK digest assets** — the built release APK must contain all four generated assets
  (`assets/whisper-libs.sha256`/`.commit`, `assets/llama-libs.sha256`/`.commit`), and the recorded
  whisper digests must byte-match what the `whisper-libs-<pin12>` release actually serves. Present
  is not enough: whisper.cpp does not build reproducibly across toolchains, so digests hashed from
  the runner's own compile would describe binaries no install is ever served — a download would
  verify-fail on a user's phone and Whisper could never be enabled.
- **Published-runtime gates** — `./scripts/vox check whisper-published` and
  `./scripts/vox check llama-published` verify that the runtime release each pin addresses is
  actually published (the llama one addressed by the build fingerprint from
  `scripts/llama_build_pin.sh` — see §13). The runtime releases are published by hand, so a pin can
  move without them; without the gate, the APK would be built against one source while every install
  downloads another.

### Triggering a release

- **Push to `main` that touches that app's `build.gradle.kts`** — the normal path. A "Detect
  versionCode bump" step asks GitHub whether a Release already exists for the computed tag; if it
  does, the rest of the job is skipped (`if: steps.check_bump.outputs.changed != 'false'` on every
  later step) — so pushing unrelated changes never triggers a rebuild, only an actual version bump
  does.

  It asks GitHub rather than diffing `HEAD~1`: when a push lands more than one commit at once,
  `HEAD~1` is *inside* that push and already shows the bumped value, so `prev == curr` would skip the
  release for a version that has never been published.
- **`workflow_dispatch`** — a manual run builds, and publishes only if its `publish` input is ticked
  (`gh workflow run release-<app>.yml -f publish=true`). The default is off so that an exploratory
  dispatch cannot cut a GitHub Release. `check_bump` doesn't run on a dispatch at all, so a
  deliberate dispatched publish can release a version whose tag already exists — which is how a build
  that succeeded but failed to publish gets recovered.
- **A direct tag push** (e.g. `git push origin calendar-v0.5`) also triggers the workflow (its
  `on.push.tags` pattern) and publishes under that exact tag.

Each release is serialised per app (`concurrency: release-<app>`, queued rather than cancelled), so
two pushes landing together can't both force-move the same tag and both delete the same release.

### Tag naming (`.github/actions/compute-release-tag`)

A shared composite action is the single source of truth for the `<app-prefix>-v<versionName>` tag
convention (e.g. `calendar-v0.5`, `commander-v0.7-beta`), so no `release-*.yml` computes it. On a
`main` push or `workflow_dispatch`, it reads `versionName` straight
out of that app's `build.gradle.kts`; on a direct tag push, it passes the pushed tag straight through
instead of recomputing it. It also derives `is_prerelease` from whether the version name contains
`-beta`/`-rc`/`-alpha` (used to mark a GitHub Release as a pre-release).

### Build → sign → publish

1. Unit tests (`./gradlew :vox-<app>:testDebugUnitTest`).
2. Decode the shared release keystore from a base64-encoded repo secret (`RELEASE_KEYSTORE_BASE64`)
   into a temp file, then `./gradlew :vox-<app>:assembleRelease` with `RELEASE_KEYSTORE_PATH`/
   `RELEASE_KEYSTORE_PASSWORD` pointed at it. **All six apps are signed with the same certificate**
   (one `vox-apps` keystore alias, not one per app) — this is load-bearing for the cross-app
   `:core:ipc` contract's signature-level permissions and first-party routing check, not just a release
   convenience; see [Satellite App Guide §8.1](SATELLITE_APP_GUIDE.md#81-signature-level-permissions-are-the-entire-trust-mechanism)
   for exactly why, and what breaks silently if a new app in this monorepo ever uses a different alias.
3. The APK is renamed to `Vox<App>-<tag>.apk` (e.g. `VoxCalendar-calendar-v0.5.apk`) and published via
   `softprops/action-gh-release`, which creates the GitHub Release, uploads the APK as an asset, and
   auto-generates release notes from the commits since the previous tag.

Publishing force-moves the app's tag onto the built commit with `git tag -f` + `git push --force`.
That step has exactly one failure mode, and it is worth knowing before it bites: `GITHUB_TOKEN` can
never hold the `workflows` scope, and that scope gates making a ref point at a tree whose
`.github/workflows` content differs from the repository's own. So the push is refused when a workflow
edit lands on `main` while a release is building, because the tag then moves onto a commit whose
workflow files are already stale — `refusing to allow a GitHub App to create or update workflow …`.
Routing the same operation through the `git/refs` REST API is subject to the identical rule and only
replaces that message with a bare `403`. **Don't edit workflow files while a release is building**;
a PAT with `workflow` scope is the only thing that would make the step immune.

### Keeping README.md's release table in sync

The top-level `README.md` has one "Build Status" table (one row per app: build badge, latest tag,
build time, APK size, direct download link) regenerated by `scripts/update_release_readme_links.sh` —
it queries this
repo's actual GitHub Releases via `gh api`, groups them by tag prefix, picks the newest per app, reads
each release's real published asset size and upload time directly from the API response (the upload
time rather than the release's `published_at`, because publishing deletes and recreates the release
for a fresh date, and a dispatched re-publish moves `published_at` without a new build at all) rather
than a hand-maintained number, which cannot track what R8 actually produces), and rewrites the content
between
two `<!-- LATEST_RELEASES:START/END -->` HTML-comment markers in place. Never hand-edit that table; the next regeneration overwrites it.

`.github/workflows/update-readme-releases.yml` runs that script automatically and commits the result
whenever a new release publishes. It's triggered by `workflow_run` (listening for each of the six
`release-*.yml` workflows to complete), **not** the more obvious `on: release: published` — because
every `release-*.yml` creates its GitHub Release using the default `GITHUB_TOKEN`, and GitHub explicitly
does not let an event triggered by `GITHUB_TOKEN` cascade into starting *another* workflow run (an
anti-recursion safeguard). A plain `release: published` trigger here would simply never fire; this was
confirmed the hard way the first time this workflow shipped (three releases published, zero README
updates) before switching to `workflow_run`, which is GitHub's documented workaround for chaining a
follow-up workflow onto one that creates its own releases/PRs with the default token.

---

*This documentation reflects the codebase as of August 2026. For the latest changes, refer to the git history.*

---

## 24. Record Creation (`:core:recordflow`, `:core:suggestions`)

Every way a record is born in this suite — a spoken command, a scanned page, a captured payment
notification — enters through `RecordFlow.dispatch` and leaves through `RecordFlow.deliver`. Three
apps had written that path independently before this module existed, and the differences between the
three were never decisions anybody made: the scan created a record whenever it had a total, the
notification channel queued or created depending on a setting, and voice had no offline path at all.

### Two halves, because the flow has two

Asking a model crosses a process boundary and comes back later through a broadcast
([§19](#19-vox-apps-ecosystem-cross-app-contract)), so no single call can carry a capture from input
to record. `dispatch` ends in `Committed(id)`, `Queued`, `Asked` or `Discarded`; `deliver` resumes
when the reply lands. Collapsing the two into one suspending call would hide the only part of the
flow that can fail silently — the answer that never comes — which is the reason the durable queue
exists in the first place.

`send` is a parameter of `dispatch` rather than something the module reaches for: delivery is
durable, retryable, and owned by the app that has the queue. `:core:recordflow` never composes a
prompt and never inspects one.

### The ladder

`LlmLevel` is eight rungs over two independent questions — how much leaves the device, and how much
of the answer lands on the record without being accepted first:

| | asks nothing | asks what is missing | asks all head fields | asks everything |
|---|---|---|---|---|
| **applies nothing** | `NONE` | `ASSIST_SUGGEST` | `HEAD_SUGGEST` | `ALL_SUGGEST` |
| **applies head** | — | `ASSIST_AUTO` | `HEAD_AUTO` | `BODY_SUGGEST` |
| **applies all** | — | — | — | `FULL` |

`AskScope` (`NOTHING`, `MISSING_HEAD`, `ALL_HEAD`, `EVERYTHING`) is what leaves; `applies(weight)` is
what is written. `FieldWeight` splits a record into `HEAD` — the fields that identify it — and `BODY`,
the fine detail, because they fail differently: a wrong vendor is visible at a glance, a list of
plausible line items with invented amounts reads as data. The enum's `init` block refuses the
combinations that would be incoherent (applying a weight the level never asked about, applying body
without head), so an impossible rung cannot be constructed.

`RecordFlowPolicy.decide` switches on `level.asks` alone. That is what makes the offline path not a
second implementation: it is the same call with a level that asks nothing, which is why three
parallel model-free creators and a per-app dial could be deleted rather than kept in step.

**The level governs the reply as well as the reading.** A rung promising "send everything, write
nothing" while the delivery half wrote everything anyway would be the exact failure the contract
exists to prevent — so `deliver` passes `effectiveLevel::applies` into `commit`, and what a rung
declines to write is offered on the record instead.

### What a satellite declares

`FlowSupport(source, supported, default, suggestsAnswers, weights)` states what a flow can honour.
A satellite with nowhere to hold a proposal cannot offer the rungs that make one; a flow whose record
has no fine detail declares `weights = setOf(FieldWeight.HEAD)` and its settings card draws one
checkbox rather than two. A stored level outside `supported` falls back to `default` with a loud log
— the only way to arrive there is a rung withdrawn from under a saved setting, which is a mismatch
somebody should see rather than a state to accommodate quietly.

`ui/RecordFlowLevelCard` renders the two questions rather than eight compound labels, drawing only
what the passed `FlowSupport` admits.

### Proposals (`:core:suggestions`)

A rung that asks but does not apply has to put the answer somewhere. `SuggestionStore` is a key/value
`FieldSuggestion` table keyed by record: `offer`, `offered(recordId)`, `accept`, `dismiss`, `clear`,
`sourceOf`, `disposeIfSpent`. Each app declares its own `SuggestableField(key, label, weight)` set —
which fields of its record may be proposed at all is the app's decision, not the module's.

`OfferedSuggestion` is what a screen reads back — the field key, the proposed value, and which
capture produced it, so dismissing the last one from a scan can dispose of that scan's photographs
too (`disposeIfSpent`). A proposal nobody will ever be shown is a photograph nobody will ever delete.

A target also declares what accepting is allowed to *do*: `AcceptMode.WRITES` puts the value on the
record, `AcceptMode.STAGES` puts it in the draft the screen is holding and lets that screen's Save
reach the database. The distinction is declared rather than assumed because getting it wrong is
invisible from the outside — a proposal written straight through looks identical to one staged, right
up to the moment somebody cancels an edit and finds part of it kept. `SuggestionStore.accept` refuses
outright on a `STAGES` target rather than leaving the refusal to an `applyValue` that returns false
and hopes. `WRITES` is the default only because it is the behaviour needing no cooperation from a
screen; a screen holding a draft must say so.

Note the ordering constraint in `deliver`: the record is written from what the device proved either
way, even at a level that writes none of the answer. A proposal has to be attached to something.

### Reference

`core/recordflow/src/main/java/com/voxapps/recordflow/` (`RecordFlow.kt`, `RecordFlowSpec.kt`,
`RecordFlowPolicy.kt`, `LlmLevel.kt`, `ui/RecordFlowLevelCard.kt`),
`core/suggestions/src/main/java/com/voxapps/suggestions/`. The seven implementations live under each
app's `domain/llm/`. The satellite-facing tutorial is
[`SATELLITE_APP_GUIDE.md` §12](SATELLITE_APP_GUIDE.md#12-record-creation-the-shape-every-path-ends-in-corerecordflow).

---

## 25. Document Reading (`:core:docread`)

What a scanned document yields before any model is asked. Moved out of `vox-expenses` so the reading
is one implementation rather than one per app; no Android dependency except `ReceiptTemplates`, which
needs a `Context` for `RemoteSchema`.

### Rows and totals prove each other

The two are read as **combinations**, not in sequence. A footer pattern proposes what the document's
totals are, an items pattern proposes what its rows are, and the pair is accepted only when the rows
sum to one of those totals **to the cent**. Neither half can be checked alone — a set of totals with
nothing summing to it is a guess, and a set of rows with nothing to compare against is a guess — so
the unit of acceptance is the pair, and `ScanReading.of` searches until one closes.

Matching is to the cent rather than to a percentage: with a list of candidates, a loose threshold
eventually crowns a wrong reading by luck.

**Where nothing closes, no items are emitted.** An empty list is a record a person completes; an
invented one is a record they must first notice is wrong.

### The shapes are data

`ReceiptTemplates` serves header/items/footer patterns from signed schema
([§17](#17-dynamic-json-configuration)), so a format nobody anticipated is a repository edit rather
than an app release. The three regions are independent lists and any header may combine with any
items pattern and any footer pattern, because they vary independently in the wild.

Published patterns are tried **before** the compiled-in battery, and the battery follows them rather
than being replaced by them. The substitution it replaced was quietly subtractive: an install that had
ever fetched a file read exactly what that file described, so a shape the library itself learned to
read reached only the installs whose fetch had never succeeded. Appending is free by the battery's own
rule — a template that fails to reconcile emits nothing — so the worst an extra one can do is be tried
and lose. A published pattern restating a built-in by id replaces it rather than stacking on top,
so a correction is not shadowed by the version it corrects.

### The parts

- `ScanReading.of` — the entry point; iterates footer candidates × item patterns.
- `LineItemBattery` — the patterns, strictest first, and the arithmetic that accepts one. Order is the
  whole priority mechanism.
- `ReceiptSections` — splits the OCR text into header/items/footer, and keeps the geometric
  reconstruction apart from the reading-order text. Counting both would let one row be read twice, and
  then no sum can reconcile.
- `TableItemsPreParse` / `ColumnRoleInference` / `ColumnHeaderDetector` — the columnar path, which
  resolves which column is which by arithmetic rather than by declared order.
- `CursorScanner` — two cursors over the whole text, for pages that arrived with no usable structure.
- `InvoiceTotalsReconciler` — the verdicts (`RECONCILED`, `UNTESTABLE`, …), the repair of shifted
  captions, and the rule that a candidate which is a tax component of another is not the total.
- `ReceiptTotalRegexParser` — the compiled-in fallback: the largest labelled total, with the
  runners-up offered afterwards as further candidates so the arithmetic can settle which was real.
  A document may print several honest totals — a restaurant bill labels every suggested-tip column
  "Total", each above what was charged.
- `HeaderReader` / `FooterReader` / `TaxBreakdown` — the letterhead, the totals block, and the
  net/VAT/gross reconciliation that derives only from read figures and never from a rate.

### Fixtures

`core/docread/src/test/resources/` holds verbatim OCR of real documents, kept as it came — misread
characters, stray markers and all. They existed before the code that reads them, which is the point:
a constructed fixture can be shaped, unconsciously, to fit the code it exercises.

---

### Schema catalogue (`:core:services`)

`SchemaCatalog` is what a satellite asks "which schemas exist, and where do they come from" — the
bundled copy shipped in assets, or the signed remote one fetched per app from its own repo URL. A
fetched schema changes an install's behaviour, so it must carry a valid signature before it is
allowed to: see `SchemaSignature`, and the monotonic-version rule that stops an old, validly-signed
manifest being replayed over a newer one.

---

## 26. Where a Record Happened (`:core:location`)

A city name on a record, resolved without a map SDK and without a Google dependency.

**`VoxLocationResolver`** answers one question — what to call where you are — from three sources in
order of cost: a cached fix, a live one, or a home town somebody typed. **`LocationCacheTtl`** is why
the cache exists at all: a phone that has not moved does not need its radio woken for every capture,
and a fix minutes old is the same answer a fresh one would give. `ResolvedLocation` carries which
source answered, so a caller can tell a real fix from a fallback rather than treating them alike.

**`VoxNominatimGeocoder`** turns coordinates into a name against OpenStreetMap's Nominatim — an
open service rather than a bundled SDK, consistent with the rest of the suite. Queries are
diacritic-folded before matching, so a place typed without them still finds itself. `VoxPlace` and
`LocationPart` keep the pieces a caller may want separately (city, county, country) rather than one
formatted string nothing can take apart.

**`VoxLocationField` / `VoxLocationSettingsCard`** are the shared UI: a field with a picker on every
screen that records a place, and one settings card for the cache TTL, the home town and the "always
use this location" switch that skips GPS entirely. Four screens across three apps use the field, which
is the reason it is here rather than in any one of them.

`EphemeralLocationStore` holds a fix for the length of one capture — long enough for a reply to come
back and be filed, short enough that nothing outlives the record it was for.

---

## 27. Backup, and Sync Between Two Phones (`:core:backup`, `:core:voxconnect`)

**`:core:backup`** is the shape every app's export/import takes, so that Vox Hub can drive all six
without knowing what any of them stores. `VoxBackupDocument` is the envelope; each app fills its own
section. `VoxSettingsRoundTrip` is what keeps a settings backup honest — a field added to an app's
settings travels without anybody remembering to add it to a hand-written allowlist, which is the
failure that loses a setting silently. `VoxBiometricGate` refuses an export or import while an app is
locked, so a lock is not merely a UI state. `VoxSnapshotReplaceImporter` implements the three restore
modes (merge, additive, full override) once rather than six times, and `mergeByName` is the shared
"land on the row this device already has" rule that keeps a restore from duplicating what it finds.

**`:core:voxconnect`** is sync between two phones with no cloud in the middle. `VoxConnectPairing`
exchanges keys over NFC — a tap is a proof of proximity no network can forge — and the transfer
itself runs over Bluetooth through `VoxConnectServer`, since NFC carries a key comfortably and a
database not at all. `AesGcmCipher` encrypts the payload with the paired key; `PairedDeviceStore`
remembers which devices are trusted. A QR path (`VoxConnectQrScanner`) exists for devices whose NFC
cannot be used to read its own address — a platform restriction that shaped the design rather than a
preference.

Merging is `:core:datahygiene`'s job, not this module's: what arrives is a delta, and which side of a
conflict wins is a data question rather than a transport one — see §21.

---

## 28. Home-Screen Widgets (`:core:widget`)

Jetpack Glance widgets across four apps, sharing the parts that would otherwise be written four
times: `WidgetDayFormats` and `DaySeparatorStyle` (how a day is headed and divided), `WidgetDayChrome`
(the border, the today tint, the padding a widget is given), and `WidgetScanRow` (the capture row a
widget offers).

The constraint that shapes all of it: a Glance widget renders no vectors of its own and holds no
session. That is why a category's icon is stored as text (§ the `:core:design` shared components) —
text survives into a widget where a drawable reference would not — and why every widget reads its
data through the app's state manager rather than caching its own, since a widget woken by the system
has no idea how long it has been asleep.
