# VoxCommander

<p align="center">
  <strong>On-device voice assistant for Android — wake word, STT, NLU, and intent routing, all running locally.</strong>
</p>

---

## Features

- **Wake Word Detection** — Always-on listening with Vosk (template mode + voice print), Picovoice Porcupine, or OpenWakeWord
- **External Trigger** — Automation apps (MacroDroid, Tasker) can trigger voice assistant via broadcast intent
- **Speech-to-Text** — Whisper.cpp (on-device, GGML models) with multilingual support
- **Natural Language Understanding** — Triple AI Brain: FastMap regex (L1) → Primary LLM (L2) → Offline fallback (L3)
- **Intent Routing** — Unified `NluIntent` → `IntentHandler` pipeline with per-domain app resolution
- **App Management** — Default apps per domain, app aliases, custom domains, return-to-previous-app
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
git clone https://github.com/razvan-eduard/VoxCommander.git
cd VoxCommander

# Build (debug)
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Launch
adb shell am start -n com.voxcommander.app/.MainActivity
```

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

## Key Technologies

| Component | Technology |
|-----------|-----------|
| UI | Jetpack Compose, Material 3 |
| STT | Whisper.cpp (GGML, on-device) |
| Wake Word | Vosk, Picovoice Porcupine, OpenWakeWord |
| NLU | OpenAI API, Gemini Nano (on-device), Local LLM (llama.cpp) |
| TTS | Android TextToSpeech, Piper TTS (sherpa-onnx) |
| Storage | DataStore (preferences), EncryptedSharedPreferences, Room |
| Media | Spotify App Remote SDK, Spotify Web API, MediaSession API |
| YouTube | NewPipe Extractor (on-device), Piped API (cloud) |
| Navigation | Waze, Google Maps (geo: deep links) |
| Search | DuckDuckGo, Wikipedia, Google News, WeatherAPI |

## Project Structure

```
app/src/main/java/com/voxcommander/app/
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
| Local LLM | On-device | llama.cpp model (GGUF) |
| FastMap | On-device | Regex rules (no ML) |

### Wake Word Engines

| Engine | Type | Models |
|--------|------|--------|
| Vosk | On-device | Template mode + voice print verification |
| Porcupine | On-device | Picovoice models (requires access key) |
| OpenWakeWord | On-device | ONNX models (open-source) |

### YouTube Search Engines

| Engine | Type | Description |
|--------|------|-------------|
| Piped API | Cloud | Uses Piped instances to search for video IDs |
| NewPipe Extractor | On-device | Parses YouTube directly to find video IDs |

Both engines resolve a search query to a YouTube video ID, then launch `youtu.be/{id}` as an intent to whichever app the user has selected as default for audio (LibreTube, NewPipe, YouTube, etc.).

### Dynamic JSON Configuration

VoxCommander uses external JSON files for extensible configuration. These ship in `app/src/main/assets/` and can be hot-reloaded from a remote GitHub repo at runtime — no app update needed to add models, search providers, or normalization rules.

| File | Purpose | Location |
|------|---------|----------|
| `models.json` | AI/ML model definitions (Whisper, Vosk, Piper TTS, wake word), NLU prompt template, engine metadata | Repo root → copied to assets at build time |
| `search_definitions.json` | Search provider definitions (DuckDuckGo, Wikipedia, Google News, GNews, WeatherAPI, Open-Meteo) — categories, endpoints, API key requirements, response parsers | Repo root → copied to assets at build time |
| `normalization.json` | 3-layer text normalization rules per language (abbreviations, regex interceptors, cleanup) — corrects STT output before NLU processing | `app/src/main/assets/normalization.json` |

**How it works:**
- At build time, `copyModelsJson` and `copySearchDefinitions` Gradle tasks copy the JSON from repo root into `assets/`
- At runtime, the app checks the remote repo (`modelRepoBaseUrl` setting) for newer versions and downloads them if the schema version is higher
- Adding a new search provider or model = just update the JSON, no code changes required

### External Voice Trigger

Automation apps like MacroDroid or Tasker can trigger the voice assistant without a wake word by sending a broadcast intent:

```bash
adb shell am broadcast -a com.voxcommander.app.TRIGGER_VOICE
```

**MacroDroid setup:**
1. Create a new macro
2. Add trigger (e.g., button press, NFC tag, schedule)
3. Add action → **Intent Action**
4. Set action: `com.voxcommander.app.TRIGGER_VOICE`
5. Target: Broadcast

**Tasker setup:**
1. Create a new task
2. Add action → **System** → **Send Intent**
3. Action: `com.voxcommander.app.TRIGGER_VOICE`
4. Type: Broadcast
5. Target package: `com.voxcommander.app`

Enable/disable in Settings → App Manager → External voice trigger toggle.

## License

This project is for personal use. See individual library licenses for third-party dependencies.

## Contributing

This is a personal project. If you'd like to contribute, please open an issue first to discuss your changes.
