# VoxCommander

<p align="center">
  <strong>On-device voice assistant for Android — wake word, STT, NLU, and intent routing, all running locally.</strong>
</p>

<p align="center">
  <a href="https://github.com/razvan-eduard/VoxApps/actions/workflows/release-commander.yml">
    <img src="https://github.com/razvan-eduard/VoxApps/actions/workflows/release-commander.yml/badge.svg" alt="Build APK" />
  </a>
  <a href="https://github.com/razvan-eduard/VoxApps/releases">
    <img src="https://img.shields.io/github/v/release/razvan-eduard/VoxCommander?display_name=tag" alt="Latest Release" />
  </a>
</p>

---

> **VoxApps monorepo.** This repository hosts multiple **fully independent** apps — each with its own applicationId, APK, release workflow, and adaptive launcher icon; the only *runtime* link is an optional native Android Intent (the Vox contract, below). Today: **`vox-commander`** (the voice assistant below, `com.voxapps.commander`), **`vox-notes`** (a standalone, encrypted on-device notes app, `com.voxapps.notes`, Room + SQLCipher), **`vox-vision`** (a standalone document scanner, `com.voxapps.vision`, camera capture + on-device OCR), **`vox-expenses`** (a standalone, encrypted on-device expense tracker, `com.voxapps.expenses`, Room + SQLCipher, voice/receipt/notification-driven capture), **`vox-calendar`** (a standalone, encrypted on-device calendar, `com.voxapps.calendar`, Room + SQLCipher, colored layers + ICS import/export + voice-created events), and **`vox-hub`** (a lightweight, database-free backup/restore utility, `com.voxapps.hub`, that discovers every installed Vox app and exports/imports their data as one JSON file). A few `:core:*` Gradle modules (`design` theming, `ipc` contract types, `wakeword` a vendored+patched OpenWakeWord fork, `calendar` a shared month-paged agenda view, `apppicker` a shared searchable app-selector card) are compiled into one or more apps for code reuse — they carry no shared runtime state, just library code. See [Vox Notes](#vox-notes), [Vox Vision](#vox-vision), [Vox Expenses](#vox-expenses), [Vox Calendar](#vox-calendar), and [Vox Hub](#vox-hub) below for what those apps do; the rest of this README covers `vox-commander`.

## Features

- **Wake Word Detection** — Always-on listening with Vosk (template mode + voice print), Picovoice Porcupine, or OpenWakeWord
- **External Trigger** — Automation apps (MacroDroid, Tasker) can trigger voice assistant via broadcast intent
- **Speech-to-Text** — Whisper.cpp (on-device, GGML models) with multilingual support
- **Natural Language Understanding** — Triple AI Brain: FastMap regex (L1) → Primary LLM (L2) → Offline fallback (L3)
- **Intent Routing** — Unified `NluIntent` → `IntentHandler` pipeline with per-domain app resolution
- **App Management** — Default apps per domain, app aliases, custom domains, return-to-previous-app
- **Vox Apps ecosystem** — Companion apps (e.g. Vox Notes) self-register their voice capabilities via the `:core:ipc` contract; Commander discovers them at warmup, adds their domains to the NLU, and routes commands over a local JSON bus (create/read → spoken back via TTS)
- **Media Control** — Spotify (Web API + App Remote), YouTube search via Piped API or NewPipe Extractor, playback on any selected app (LibreTube, NewPipe, etc.), media session control
- **Text-to-Speech** — Android TTS or Piper TTS (on-device neural voices via sherpa-onnx)
- **Search** — Web search via DuckDuckGo, Wikipedia, Google News, GNews, WeatherAPI, Open-Meteo
- **Navigation** — Waze, Google Maps deep linking
- **Messaging** — WhatsApp, Telegram, SMS via `ACTION_SEND`
- **System Controls** — Volume, WiFi, Bluetooth, GPS toggles
- **Multi-language** — English, Romanian, and extensible via `LanguageManager`
- **Overlay UI** — Floating voice overlay with transcription and status
- **Model Downloads** — On-demand DLC for Whisper models, Piper voices, wake word models
- **Settings** — Compose-based settings with 7 tabs (General, App Manager, Services, Integrations, Models, Advanced, Permissions)

## Requirements

- **Android 10+** (API 29)
- **arm64-v8a** architecture
- ~19MB APK (release, without Whisper native libs — downloaded on demand)
- Optional: OpenAI API key for cloud NLU, Spotify Client ID for media control

## Quick Start

```bash
# Clone
git clone https://github.com/razvan-eduard/VoxApps.git
cd VoxCommander

# Build (debug)
./gradlew :vox-commander:assembleDebug

# Install on connected device
./gradlew :vox-commander:installDebug

# Launch
adb shell am start -n com.voxapps.commander/.MainActivity
```

### Run Tests

```bash
# Unit tests (JVM — no device needed)
./gradlew :vox-commander:testDebugUnitTest

# Instrumented tests (requires connected device/emulator)
./gradlew :vox-commander:connectedAndroidTest
```

212 unit tests covering: intent taxonomy, NLU decision map, AppState/AppStateManager, AppSettings (external trigger, return-to-previous-app), model management, search providers, FastMap engine, and more.

> Swap `:vox-commander` for `:vox-notes`, `:vox-vision`, `:vox-expenses`, `:vox-calendar`, or `:vox-hub` in any of the commands above to build/install/test the companion apps instead — each has its own `assembleDebug`/`installDebug`/`testDebugUnitTest` tasks.

## Download APK

Pre-built APKs are available on the [Releases page](https://github.com/razvan-eduard/VoxApps/releases). Each release is built automatically via GitHub Actions.

To create a new release:
```bash
git tag v1.1
git push origin v1.1
```
GitHub Actions will build the APK and publish it as a release automatically.

> **Note**: Release APKs are signed with a consistent per-app release key (so device updates work
> seamlessly across versions). Install via `adb install VoxCommander-v*.apk` or enable "Install unknown
> apps" on your device. Local `./gradlew assembleRelease` builds outside CI are unsigned unless you set
> `RELEASE_KEYSTORE_PATH`/`RELEASE_KEYSTORE_PASSWORD` yourself.

### First Run Setup

1. **Grant permissions** — Microphone, notifications (foreground service), overlay display
2. **Download a Whisper model** — Settings → Models → Download (tiny/base/small)
3. **Select wake word engine** — Settings → Voice Engines → Vosk / Porcupine / OpenWakeWord
4. **Configure NLU engine** — Settings → Intent Engines → OpenAI / Gemini Nano / Local LLM
5. **Set default apps** — Settings → App Manager → Select apps per domain (audio, maps, messaging)
6. **Enable wake word** — Settings → General → Toggle wake word detection

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    WakeWordService (FG)                      │
│  Vosk / Porcupine / OpenWakeWord → always-on listening      │
└──────────────────────────┬──────────────────────────────────┘
                           │ wake word detected
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    VoiceManager                             │
│  Records audio → Whisper.cpp STT → text transcript          │
└──────────────────────────┬──────────────────────────────────┘
                           │ transcribed text
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              IntentDecisionMap (Triple AI Brain)            │
│  L1: FastMap (regex) → L2: Primary LLM → L3: Fallback      │
│  Output: NluIntent (domain, action, targetApp, params)      │
└──────────────────────────┬──────────────────────────────────┘
                           │ NluIntent
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    IntentRouter                             │
│  AppResolver → picks handler → executes Android Intent      │
│  Return-to-previous-app if configured                       │
└──────────────────────────┬──────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
  AudioHandler      NavigationHandler    SystemHandler
  Spotify/LibreTube  Waze/Google Maps    Volume/WiFi/BT
  NewPipe/Piped      geo: URI            Settings intents
```

### Vox Apps ecosystem (cross-app plugin bus)

Beyond routing to arbitrary Android apps, Commander is a **local plugin hub** for companion "satellite"
apps that speak the **Vox contract** (`:core:ipc`, a contracts-only library — no runtime coupling).
Each app stays a fully independent product; a user can install any subset.

- **Self-registration** — a satellite declares an exported `VoxCommandReceiver` (guarded by a
  `signature`-level custom permission) with `<meta-data>` advertising the NLU **domain** it owns and
  the **actions** it accepts. Nothing is hardcoded in Commander, and no per-app entry in `intents.json`.
  A satellite can optionally declare an `nluHint` too — a free-text sentence teaching the LLM its own
  domain-specific fields (e.g. Vox Notes' `category` field). Commander surfaces it as a
  "Domain-specific extraction" line in the shared NLU prompt — no edit to `models.json` needed per app.
- **Discovery** — at warmup (and on the Integrations screen) Commander scans installed apps for the
  contract, reads their capabilities, and **merges their domains/actions into the NLU taxonomy
  dynamically**. A user's own app that implements the contract appears automatically.
- **Command bus** — Commander authors a small JSON envelope (`VoxCommand`) and sends it as a broadcast:
  `create` (fire-and-forget) or `read` (ordered broadcast → the satellite returns text → Commander
  **speaks it with the TTS hook**, reusing all of Commander's TTS settings). Plaintext over Binder;
  encryption at rest stays the satellite's concern.
- **Routing when several apps claim a domain** — deterministic hierarchy:
  ① app named in the utterance → ② user **star** (Settings → Default Apps) → ③ **first-party**
  (signed with Commander's own key, `checkSignatures == SIGNATURE_MATCH`) → ④ single third-party →
  ⑤ 2+ third-party with no star = first discovered (voice disambiguation is a planned follow-up).
  So a first-party app (e.g. Vox Notes) wins over a third-party alternative silently, while the user
  can always override with a star. Discovered apps + their First-party/Third-party status show under
  **Settings → Integrations → Vox Apps**.
- **Generic LLM hook** — beyond the create/read command bus, any first-party satellite can also ask
  Commander's *currently-selected* LLM to process an arbitrary prompt: broadcast `ACTION_LLM_PROCESS`
  with a `VoxLlmRequest{sourcePackage, task, promptText}`, handled by a WorkManager job
  (`LlmHookReceiver` → `LlmHookWorker`, so a slow LLM call never risks an ANR on a receiver with no
  visible UI), then get an async `ACTION_LLM_RESULT` reply (`VoxLlmResult{task, status, rawJson}`)
  targeted back at `sourcePackage`. Commander never interprets `task`/`promptText`/`rawJson` — building
  the prompt and parsing the result is entirely the caller's concern, so new LLM-backed satellite
  features ship with zero Commander changes. Consumers today: Vox Notes' **Auto-Merge Categories** and
  **Note cleanup** (duplicate detection), and Vox Vision's OCR-text cleanup (see below).
- **Shared theming** — every app (Commander and all four satellites) renders through the same
  `:core:design` `VoxTheme` composable and exposes the same user-facing controls in its General
  settings: a System/Light/Dark picker plus a "Colored (Material You)" toggle for Android 12+ dynamic
  color, persisted independently per app.
- **Vox Vision's scan-to-note flow** — a second satellite, `vox-vision` (domain `vision`), turns the
  camera into a document scanner: live brightness/edge detection auto-triggers a capture, OpenCV crops
  it to the detected document, on-device PaddleOCR (`ppocr-sdk`) recognizes the text, then the generic
  LLM hook above cleans the raw OCR text into a title + body (and can suggest a category, reusing Vox
  Notes' own voice-note category-resolution logic) before forwarding it to Vox Notes as a new note.
  Vision can also be launched directly by another satellite (`startActivity` with an explicit
  `VoxOcrRequest` payload) for a hands-free "scan → auto-submit" pending-request flow — a direct
  explicit-intent launch rather than a broadcast, because Android's background-activity-launch
  restriction is evaluated against the *calling* app's foreground state, not Vision's, so this needs no
  notification-tap workaround.

```
Commander ──VoxCommand{op,text,domain}──▶ satellite VoxCommandReceiver
   ▲                                              │ create → DB append
   └────────── VoxResult{ok,text} ────────────────┘ read → returns text → TtsManager.speak(...)

satellite ──VoxLlmRequest{task,promptText}──▶ LlmHookReceiver/Worker (Commander's LLM)
   ▲                                                          │
   └──────────── VoxLlmResult{task,rawJson} ──────────────────┘ (async, explicit intent back to caller)
```

Five satellites ship in this repo today: **`vox-notes`** (domain `notes`, actions `create`/`read`/
`export`/`import`), **`vox-vision`** (domain `vision`, a note/expense *producer* rather than a
voice-command consumer), **`vox-expenses`** (domain `expenses`, actions `create`/`read`/`export`/
`import`), and **`vox-calendar`** (domain `calendar`, actions `create`/`read`/`export`/`import`) —
Vision's OCR-cleanup hook can target either Notes or Expenses depending on which flow launched the scan
(`ocr.task` meta-data on the satellite's `OcrResultReceiver`). **`vox-hub`** is a fifth, non-NLU
satellite: it never registers a `VoxCommandReceiver` domain (nothing to say "hey commander" to), it's a
pure IPC *client* that calls the other four apps' `export`/`import` actions to build/restore one JSON
backup file — see [Vox Hub](#vox-hub) below.

### Vox Notes

Standalone, encrypted on-device notes app (`com.voxapps.notes`, Room + SQLCipher). Voice-created
through Commander (`create`/`read`) or used entirely on its own.

- **Categories** with a coverflow-style picker; **Auto-Merge Categories** asks Commander's LLM hook to
  find and merge near-duplicate categories (e.g. "Shopping" / "Groceries")
- **Note cleanup** — the same LLM hook finds near-duplicate/redundant notes, but unlike category
  merge this only *proposes* groups (kept note + duplicates); the user reviews and taps **Apply
  selected** before anything is deleted — nothing is ever auto-deleted
- Both triggers are available **on-demand** and on a **schedule** (off/daily/weekly/monthly)
- **Scan-to-note** — receives raw OCR text from Vox Vision, sends it through the LLM hook to get a
  clean title/body and a suggested category, then creates the note
- Editor UI: tap a note's title to expand/collapse it in place (collapse via a dedicated chevron
  above the title, separated from the body by a shaded header)
- **Calendar view** (optional, off by default) — a month-paged agenda view (shared `:core:calendar`
  module) instead of the plain chronological list, with a peek into the tail/head of adjacent months
  and a "Today" button; month/weekday names follow the app's own language setting, not the device locale
- Category color picker — random colors pick the hue farthest from every existing category instead of
  a plain random draw, the swatch row scrolls to reach all 10 presets, and the selected swatch gets a
  clear shadow+ring indicator
- Multi-language UI (English, Romanian, German, French)

### Vox Vision

Standalone document scanner (`com.voxapps.vision`, `com.voxapps.vision.VisionActivity`) — no voice
commands in, only OCR text out.

- **Camera capture** (CameraX) with a live overlay of the detected document bounds
- **Auto-capture** — a throttled `ImageAnalysis` pass runs Otsu-threshold brightness-blob detection
  (deliberately not the stricter quad detection used for the final crop, since a document extending
  past the frame edge can't close into a 4-sided contour) and auto-triggers a capture once the bounds
  are stable; sensitivity is user-configurable (low/medium/high)
- **Edge cropping** — the final captured frame is cropped to the detected document quad via OpenCV
  (`DocumentCropper.kt`), built from source against a vendored OpenCV (see
  [`docs/BUILD_TIME_DEPENDENCIES.md`](docs/BUILD_TIME_DEPENDENCIES.md))
- **On-device OCR** via a vendored, patched PaddleOCR Android SDK (`:vendor:ppocr-sdk`) — no network
  round-trip for text recognition
- Recognized text is cleaned up and titled via Commander's generic LLM hook, then forwarded to Vox
  Notes as a new note (see [Vox Vision's scan-to-note flow](#vox-apps-ecosystem-cross-app-plugin-bus)
  above)
- Works fully standalone (its own launcher icon) or as a **pending-request target** launched directly
  by another satellite for a hands-free "scan → auto-submit" flow
- Multi-language UI (English, Romanian, German, French)

### Vox Expenses

Standalone, encrypted on-device expense tracker (`com.voxapps.expenses`, Room + SQLCipher). Voice-created
through Commander (`create`/`read`), scanned from a receipt via Vox Vision, captured automatically from
bank/payment notifications, or entered by hand.

- **Three capture paths, three prompts** — voice (`ExpenseParsePromptBuilder`), receipt OCR
  (`ExpenseScanCleanupPromptBuilder` — extracts vendor, bank, per-item net/VAT/gross when printed, and
  prioritizes the receipt's own printed total over recomputing it from line items), and notification
  capture (`NotificationExpenseParsePromptBuilder` — a deliberately narrower extraction: title/amount/
  currency/vendor/category plus an `isPayment` triage flag, since notification text isn't guaranteed to
  even be a transaction) — all three route through Commander's generic LLM hook, just with different
  `task` IDs and prompts suited to how much structure each source actually has
- **Notification capture** — an opt-in `NotificationListenerService` inspects notifications only from
  apps the user explicitly allowlists (Settings → Notification capture); every candidate expense is a
  **pending suggestion** the user must approve or dismiss, nothing is ever created automatically. The
  allowlist picker is a shared `:core:apppicker` card (search + all/user/system filter) backed by a
  persisted launcher-apps cache — scanned once ever, reloaded from cache on every later launch, with a
  manual "Rescan Apps" button for when a new app is installed
- **Line items & VAT** — optional per-item net/VAT/gross breakdown, shown only when enabled (most
  receipts don't carry this detail); a red warning banner appears if the total doesn't match the sum of
  line items, without blocking editing
- **Currency & exchange rates** — a default currency for new expenses, a separate home currency reports
  convert *into* when expenses mix currencies, and a locale-independent decimal separator setting (comma
  vs. period) so typed amounts round-trip correctly regardless of device locale
- **Spending limits** — per-category/per-period budget alerts, checked by a scheduled `WorkManager` job
  (`SpendingLimitCheckWorker`) and delivered as a system notification
- **Category auto-merge & expense cleanup** — the same Commander LLM-hook pattern as Vox Notes: auto-merge
  finds and merges near-duplicate categories, expense cleanup *proposes* duplicate-expense groups for the
  user to review before anything is deleted; both run on-demand or on a schedule
- **Calendar view** (optional, off by default) — same shared `:core:calendar` module as Vox Notes, plus
  bank/vendor filters and an amount ascending/descending sort; sorting by amount isn't chronological, so
  it temporarily disables the calendar view (a dismissible chip restores it)
- **Reports** — totals and by-category breakdowns, converted into the home currency
- Multi-language UI (English, Romanian, German, French)

### Vox Calendar

Standalone, encrypted on-device calendar (`com.voxapps.calendar`, Kotlin/AGP namespace
`com.voxapps.calendarapp` to avoid a dex-merge clash with the reused `:core:calendar` module — see its
own doc comment). Voice-created through Commander (`create`/`read`) or used entirely on its own.

- **Year / Month / Week / Day views** behind a collapsible sidebar; Month reuses the shared
  `:core:calendar` engine, Week/Day are a new local hour-of-day grid, Year is 12 compact mini-months
- **Colored, named layers** (e.g. Personal / Work / Moon Calendar) instead of a hierarchical
  category tree — flat layers plus optional flat tags, each layer independently toggleable and
  color-coded (random-generation + selection-ring picker mirrors Vox Expenses' category palette)
- **Natural-language event/task creation via Commander** — "dentist in a week" resolves the date
  entirely through the LLM (no local date-NLU), picks the right layer by name (exact match → fuzzy
  match via `:core:textmatch` → configurable default), and works for both timed events and due-date
  tasks
- **ICS import/export** (Settings → ICS import/export) via the `biweekly` library — spec-correct
  interop with Google Calendar/Thunderbird/Apple Calendar, independent of the Hub JSON backup path
- **Cross-app day-linking** — tapping a day shows that day's Notes and Expenses inline (a day-scoped
  `OP_READ` extension to the Vox contract) with tap-through to open Notes/Expenses pre-filtered to that
  day; the reverse direction (Notes/Expenses → open Calendar on a date) uses a plain explicit-intent
  extra, not the broadcast bus
- Recurrence is deliberately minimal (none/daily/weekly/monthly/yearly + optional until-date, expanded
  at read time) — no RRULE engine, no per-occurrence materialized rows, editing a series edits the
  whole thing
- Multi-language UI (English, Romanian)

### Vox Hub

A lightweight, database-free backup/restore utility (`com.voxapps.hub`) — not a voice-command
satellite (it registers no NLU domain), just an IPC *client* over the same `:core:ipc` contract every
other satellite implements as a server.

- **Zero hardcoded app list** — at launch it calls `VoxAppsDiscovery` to find every installed Vox app
  that advertises `export`/`import` in its manifest meta-data; a new satellite (like Vox Calendar) shows
  up automatically with no Hub-side code change
- **Export** — per-app `settings`/`data`/`both` scope selection, bundles every selected app's exported
  JSON into one dated file via `ActivityResultContracts.CreateDocument`
- **Import** — reads a previously exported file, previews a per-app record-count summary before
  anything is written, and lets the user deselect individual apps; each target app applies its own
  snapshot-then-replace semantics (existing records for that domain are fully replaced by the import,
  not merged)
- Holds no local Room database — the only thing it persists itself is its own theme preference

## Key Technologies

| Component | Technology |
|-----------|-----------|
| UI | Jetpack Compose, Material 3 |
| STT | Whisper.cpp (GGML, on-device) |
| Wake Word | Vosk, Picovoice Porcupine, OpenWakeWord (vendored fork, `:core:wakeword`) |
| NLU | OpenAI API, Gemini Nano (on-device), Local LLM (MediaPipe GenAI — Qwen 2.5 / Gemma 3) |
| TTS | Android TextToSpeech, Piper TTS (sherpa-onnx) |
| Storage | DataStore (preferences), EncryptedSharedPreferences, Room |
| Media | Spotify App Remote SDK, Spotify Web API, MediaSession API |
| YouTube | NewPipe Extractor (on-device), Piped API (cloud) |
| Navigation | Waze, Google Maps (geo: deep links) |
| Search | DuckDuckGo, Wikipedia, Google News, WeatherAPI |

## Project Structure

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
│   │   ├── interpreter/         # OpenAI, Gemini Nano, Local LLM, FastMap
│   │   ├── model/NluIntent.kt   # Universal intent data class
│   │   ├── taxonomy/            # IntentTaxonomy (domains, actions)
│   │   ├── registry/AppRegistry # App scanning, URI templates
│   │   ├── resolver/AppResolver # App resolution with aliases + defaults
│   │   ├── router/IntentRouter  # Central dispatcher
│   │   └── handler/             # Audio, Navigation, System, Messaging, Search
│   ├── voice/                   # VoiceManager, WakeWordCalibrator, TtsManager
│   ├── search/                  # Search providers (web, news, weather, knowledge)
│   └── localization/            # LanguageManager (i18n)
├── service/
│   ├── WakeWordService.kt       # Foreground service, always-on listening
│   ├── WakeWordEngine.kt        # Vosk wake word with template + voice print
│   ├── SpotifyRemoteManager.kt  # Spotify App Remote SDK wrapper
│   ├── SpotifyPkceManager.kt    # Spotify PKCE OAuth flow
│   ├── SpotifyWebApi.kt         # Spotify Web API (search, play, devices)
│   └── MediaSessionListenerService.kt
├── state/
│   ├── AppState.kt              # Global UI state
│   └── AppStateManager.kt       # State flow management
├── ui/
│   ├── components/              # Reusable Compose components
│   ├── screens/
│   │   ├── main/                # Listening screen, voice overlay
│   │   ├── settings/            # 7 settings tabs
│   │   ├── splash/              # Splash screen
│   │   └── rules/               # FastMap rule editor
│   └── theme/                   # Material 3 theme
└── utils/                       # Logger, NetworkMonitor, IntentUtils, etc.
```

## Configuration

### NLU Engines

| Engine | Type | Requires |
|--------|------|----------|
| OpenAI | Cloud | API key |
| Gemini Cloud | Cloud | API key |
| Gemini Nano | On-device | Pixel 8+ / compatible device |
| Local LLM | On-device | MediaPipe GenAI model (Qwen 2.5 / Gemma 3, `.task`) |
| FastMap | On-device | Regex rules (no ML) |

### Wake Word Engines

| Engine | Type | Models |
|--------|------|--------|
| Vosk | On-device | Template mode + voice print verification; typed wake word or calibrated profile |
| Porcupine | On-device | Built-in keywords (from `models.json`); requires a Picovoice access key |
| OpenWakeWord | On-device | ONNX models (open-source), bundled |

All three are defined in `models.json` and selected by capability (not hardcoded). A single **Wake Word Sensitivity** (low/medium/high) maps to a per-engine threshold; changing it prompts a confirmation and hot-reloads the running engine.

**OpenWakeWord is vendored locally as `:core:wakeword`**, not pulled from JitPack — the upstream library
runs full ONNX inference (mel-spectrogram + embedding + classifier) on every audio buffer, including
silence, which was a meaningful battery cost for always-on wake word detection. The fork carries a small
RMS silence-gate patch (`AudioRecorder.kt`) that drops silent buffers before inference ever runs. A git
submodule (`vendor/openwakeword-android-kt`) tracks the pristine upstream source; the patch itself is a
maintained diff (`core/wakeword/patches/0001-rms-silence-gate.patch`); a scheduled workflow
(`.github/workflows/sync-openwakeword.yml`) checks for new upstream releases weekly and opens a PR with
the patch already re-applied and tested (only needs a manual merge if the patch genuinely conflicts).
See `core/wakeword/NOTICE` for the full attribution and maintenance process.

### YouTube Search Engines

| Engine | Type | Description |
|--------|------|-------------|
| Piped API | Cloud | Uses Piped instances to search for video IDs |
| NewPipe Extractor | On-device | Parses YouTube directly to find video IDs |

Both engines resolve a search query to a YouTube video ID, then launch `youtu.be/{id}` as an intent to whichever app the user has selected as default for audio (LibreTube, NewPipe, YouTube, etc.).

### Dynamic JSON Configuration

VoxCommander uses external JSON files for extensible configuration. These ship in `vox-commander/src/main/assets/` and can be hot-reloaded from a remote GitHub repo at runtime — no app update needed to add models, search providers, probeable intents, or normalization rules.

| File | Purpose | Location |
|------|---------|----------|
| `models.json` | AI/ML model definitions (Whisper, Vosk, Piper TTS, OpenWakeWord, Porcupine keywords, local LLM), NLU prompt template, engine metadata + capabilities | Repo root → copied to assets at build time |
| `search_definitions.json` | Search provider definitions (DuckDuckGo, Wikipedia, Google News, GNews, WeatherAPI, Open-Meteo) — categories, endpoints, API key requirements, response parsers | Repo root → copied to assets at build time |
| `intents.json` | Capability manifest: the NLU `taxonomy` (domains/actions vocabulary) + the catalog of Android intents probed per app (action, probe URI, URI template, domain mapping) — the data behind "arbitrary dynamic intent to any app" | Repo root → copied to assets at build time |
| `normalization.json` | 3-layer text normalization rules per language (abbreviations, regex interceptors, cleanup) — corrects STT output before NLU processing | `vox-commander/src/main/assets/normalization.json` |

**How it works:**
- At build time, `copyModelsJson`, `copySearchDefinitions`, and `copyIntentsJson` Gradle tasks copy the JSON from repo root into `assets/`
- At runtime, the app checks the remote repo (`modelRepoBaseUrl` setting) for newer versions and downloads them if the schema version is higher (never downgrading below the bundled copy)
- Adding a new model, search provider, or probeable intent = just update the JSON, no code changes required

### External Voice Trigger

Automation apps like MacroDroid or Tasker can trigger the voice assistant without a wake word by sending a broadcast intent:

```bash
adb shell am broadcast -a com.voxapps.commander.TRIGGER_VOICE
```

**MacroDroid setup:**
1. Create a new macro
2. Add trigger (e.g., button press, NFC tag, schedule)
3. Add action → **Intent Action**
4. Set action: `com.voxapps.commander.TRIGGER_VOICE`
5. Target: Broadcast

**Tasker setup:**
1. Create a new task
2. Add action → **System** → **Send Intent**
3. Action: `com.voxapps.commander.TRIGGER_VOICE`
4. Type: Broadcast
5. Target package: `com.voxapps.commander`

Enable/disable in Settings → App Manager → External voice trigger toggle.

## Further Reading

- [`docs/TECHNICAL_DOCUMENTATION.md`](docs/TECHNICAL_DOCUMENTATION.md) — deep-dive on `vox-commander`'s
  architecture, wake word/STT/NLU/TTS engines, intent routing, and the cross-app Vox contract.
- [`docs/BUILD_TIME_DEPENDENCIES.md`](docs/BUILD_TIME_DEPENDENCIES.md) — monorepo-wide reference for
  every native/ML dependency that's vendored, patched, or compiled from source at build time (Vosk,
  Whisper.cpp, OpenWakeWord, OpenCV, PaddleOCR ppocr-sdk) — what gets fetched, how patches are kept as
  real diffs, how each stays in sync with upstream via a weekly `sync-*.yml` workflow, and which one
  (`sync-vosk.yml`) is judged safe enough to auto-merge on green.

## License

MIT — see [`LICENSE`](LICENSE). This covers the code in this repository; third-party dependencies keep
their own licenses (see [`docs/BUILD_TIME_DEPENDENCIES.md`](docs/BUILD_TIME_DEPENDENCIES.md) for the
vendored/patched ones). `vox-commander` additionally bundles two proprietary components not covered by
the MIT license above: the Spotify App Remote SDK (`vox-commander/libs/spotify-app-remote.aar`) and
Picovoice Porcupine (`ai.picovoice:porcupine-android`) — both closed-source, each under its own vendor
license.

## Contributing

This is a personal project. If you'd like to contribute, please open an issue first to discuss your changes.
