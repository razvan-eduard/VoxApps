# VoxCommander — Technical Documentation

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
    → SpotifyRemoteManager (set client ID)
    → SpotifyPkceManager (load persisted tokens)
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

- Fully open-source, ONNX-based wake word detection via `xyz.rementia:openwakeword:0.1.5`. Models are `wake_openwakeword` entries in `models.json` (bundled in `assets/openwakeword/`).
- **Startup warmup** — Detections in the first 1.5 s after each `start()` are ignored. Right after `start()` the mel/embedding feature buffers aren't primed and emit spurious high scores; without this guard the detect → command → re-arm cycle self-triggers into a loop.

### Sensitivity (`WakeWordSensitivity.kt`)

The low/medium/high **Wake Word Sensitivity** setting maps to a per-engine threshold in one shared, unit-tested helper. Note the engines disagree on direction:

| Setting | OpenWakeWord (score ≥ threshold) | Porcupine (`setSensitivities`) | Vosk template DTW |
|---------|------|------|------|
| high | 0.3 (easier) | 0.7 (more sensitive) | 0.35 |
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

---

## 3. Speech-to-Text (STT)

### Whisper.cpp Integration

- **Native library**: `libwhisper.so` (GGML-based, compiled via CMake)
- **Engine**: `WhisperSttEngine` in `domain/engine/whisper/`
- **Models**: Downloaded on-demand from HuggingFace (`ggml-tiny.bin`, `ggml-base.bin`, `ggml-small.bin`)
- **Release builds**: Whisper native libs excluded from APK (~166MB → ~19MB), downloaded as DLC
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
    val mediaControlType: String?     // "active_session", "default_app", "audio_button"
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
    SearchIntentHandler(settingsRepository),  // domain=search
    AudioIntentHandler(),                     // domain=audio
    NavigationIntentHandler(),                // domain=maps
    SystemIntentHandler(),                    // domain=settings, system, home
    MessagingIntentHandler(),                 // domain=messaging
    GenericLaunchHandler()                    // fallback: launch app by name
)
```

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

1. **Spotify Web API** — If target is Spotify and PKCE authorized, search + play via Web API (no UI)
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

### Spotify

#### Spotify App Remote SDK (`SpotifyRemoteManager.kt`)

- Uses Spotify's App Remote SDK (`spotify-app-remote.aar`)
- Connection requires Spotify app installed on device
- `connect()` — blocking, 30s timeout (internal use)
- `connectAsync()` — non-blocking, 60s timeout (UI use, for OAuth)
- Once connected: `playSearch()`, `playUri()`, `resume()`, `pause()`, `skipNext()`, `skipPrevious()`

#### Spotify Web API (`SpotifyWebApi.kt` + `SpotifyPkceManager.kt`)

- PKCE OAuth flow — no client secret needed
- Tokens stored in `EncryptedSharedPreferences`
- `SpotifyPkceManager.isAuthorized` — true if refresh token exists (persists across restarts)
- `SpotifyWebApi.playSearch()` — search track + play on active device (no UI shown)
- Token refresh handled automatically via `getValidAccessToken()`

### YouTube / LibreTube

#### Piped API (`PipedSearchHelper.kt`)

- Cloud-based YouTube search via Piped instances
- Multiple instances with fallback (`PIPED_INSTANCES` list)
- User selects instance + region in settings
- `searchAndPlay()` — searches Piped, gets videoId, launches `youtu.be/{id}` in target app

#### NewPipe Extractor (`NewPipeExtractorHelper.kt`)

- On-device YouTube parsing (no external API)
- Uses `com.github.teamnewpipe:NewPipeExtractor:v0.24.8`
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
| TTS | `ttsEnabled`, `ttsEngineType`, `ttsSpeechRate`, `ttsPitch`, `ttsAudioFocusMode` |
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

A `BroadcastReceiver` registered in `AndroidManifest.xml` that listens for the action `com.voxcommander.app.TRIGGER_VOICE`.

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

- **Custom permission**: `com.voxcommander.app.permission.TRIGGER_VOICE` (protectionLevel: `normal`)
- Receiver is `exported=true` but requires the custom permission
- **Setting toggle**: `externalTriggerEnabled` (default: true) — user can disable in Settings → App Manager

### Manifest Registration

```xml
<permission
    android:name="com.voxcommander.app.permission.TRIGGER_VOICE"
    android:protectionLevel="normal"
    android:label="Trigger VoxCommander Voice Assistant" />

<receiver
    android:name=".service.VoiceTriggerReceiver"
    android:enabled="true"
    android:exported="true"
    android:permission="com.voxcommander.app.permission.TRIGGER_VOICE">
    <intent-filter>
        <action android:name="com.voxcommander.app.TRIGGER_VOICE" />
    </intent-filter>
</receiver>
```

### Usage

#### ADB
```bash
adb shell am broadcast -a com.voxcommander.app.TRIGGER_VOICE
```

#### MacroDroid
1. Create macro → add trigger (button, NFC, schedule, etc.)
2. Add action → **Intent Action**
3. Action: `com.voxcommander.app.TRIGGER_VOICE`
4. Target: Broadcast

#### Tasker
1. Create task → add action → **System** → **Send Intent**
2. Action: `com.voxcommander.app.TRIGGER_VOICE`
3. Type: Broadcast
4. Target package: `com.voxcommander.app`

### Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `externalTriggerEnabled` | Boolean | `true` | Enable/disable external broadcast trigger |

UI: Settings → App Manager → External voice trigger toggle

---

## 17. Dynamic JSON Configuration

VoxCommander uses four external JSON files for extensible, hot-reloadable configuration. All ship in `app/src/main/assets/` and can be updated from a remote GitHub repo at runtime — no app update required. `models.json`, `search_definitions.json`, and `intents.json` are authored at the repo root and copied into assets by Gradle; `normalization.json` lives directly in assets.

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
- `intents` — Array of intent definitions: `action` (the literal Android action string, e.g. `android.intent.action.VIEW`), `probe_uri`, `uri_template` (with `{query}`/`{destination}`/`{contact}` placeholders), `label`, `template_action`, `requires_query`, `mime_type`

**Hot-reload**: fetched from the remote repo at startup and via the Settings "Sync JSON" button, same mechanism as `models.json`/`search_definitions.json`. If the JSON is missing/unparseable, a compact hardcoded seed (core routing intents) keeps the app functional.

**Adding a probeable intent**: add an entry to `intents.json` — no code change. `IntentCatalogTest` is a golden test asserting each SDK action constant is transcribed byte-exact (a mistyped literal would silently never match).

### normalization.json

**Location**: `app/src/main/assets/normalization.json` (not copied from repo root — ships directly in assets)

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

`normalization.json` is not copied from repo root — it lives directly in `app/src/main/assets/` since it's not hot-reloaded from remote.

---

## 18. Dependency Graph

### Core Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Jetpack Compose BOM | (latest) | UI framework |
| Material 3 | (via BOM) | Design system |
| Vosk Android | (via libs.versions) | Wake word + STT |
| Whisper.cpp | (submodule, CMake) | On-device STT |
| sherpa-onnx | v1.13.3 (JitPack) | Piper TTS |
| MediaPipe GenAI | (via libs.versions) | Local LLM (llama.cpp) |
| Google Generative AI | 0.9.0 | Gemini Nano |
| Picovoice Porcupine | 3.0.2 | Wake word engine |
| OpenWakeWord | 0.1.5 (rementia) | Wake word engine |
| ONNX Runtime | 1.22.0 | ML inference for OpenWakeWord |
| Spotify App Remote | (local AAR) | Spotify media control |
| NewPipe Extractor | v0.24.8 (JitPack) | YouTube search/parsing |
| OkHttp | (via libs.versions) | HTTP client |
| Retrofit | (via libs.versions) | API client |
| Gson | (via libs.versions) | JSON serialization |
| DataStore Preferences | (via libs.versions) | Settings storage |
| Security Crypto | (via libs.versions) | Encrypted preferences |
| Room | (via libs.versions) | FastMap rules database |
| Apache Commons Compress | 1.26.1 | Piper model extraction (.tar.bz2) |
| ProcessPhoenix | 2.1.2 | App restart |
| Browser | 1.8.0 | Chrome Custom Tabs (Spotify OAuth) |

### Build Tasks

| Task | Description |
|------|-------------|
| `autoCompileWhisper` | Checks whisper.cpp upstream and recompiles via CMake if needed |
| `autoCheckVosk` | Checks for newer Vosk version on JitPack |
| `copyModelsJson` | Copies `models.json` from repo root to assets |
| `copySearchDefinitions` | Copies `search_definitions.json` from repo root to assets |
| `copyIntentsJson` | Copies `intents.json` from repo root to assets |

All five tasks are dependencies of `preBuild`.

### Repositories

- **Google Maven** — AndroidX, Compose, MediaPipe
- **Maven Central** — OkHttp, Retrofit, Gson, Apache Commons, ONNX Runtime
- **JitPack** — Vosk, sherpa-onnx, NewPipe Extractor, OpenWakeWord
- **Picovoice Maven** — Porcupine

---

*This documentation reflects the codebase as of July 2026. For the latest changes, refer to the git history.*
