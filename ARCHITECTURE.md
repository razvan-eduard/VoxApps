# VoxCommander — Architecture Outline

> Generated architecture audit. Part 1 describes the current architecture; Part 2 lists potential problems and race conditions found in the code.

---

# PART 1 — CURRENT ARCHITECTURE

## 1. High-Level Component Map

```
┌─────────────────────────────────────────────────────────────────────┐
│                          VoxApplication                             │
│                    └── AppContainer (manual DI)                     │
└─────────────────────────────────────────────────────────────────────┘
        │
        ├── SettingsRepositoryImpl (DataStore + EncryptedSharedPrefs)
        ├── AppStateManager (singleton, reactive SSOT)
        ├── ModelDownloader / WhisperEngineManager / RemoteModelRegistry
        ├── LanguageManager (JSON translations: en/ro/de/fr)
        ├── VoxDatabase (Room) → FastMapDao (L1 regex rules)
        │
        ├── NLU ENGINES (AssistantEngine implementations)
        │     ├── FastMapEngine        (L1: local regex)
        │     ├── OpenAiInterpreter    (L2: gpt-4o-mini)
        │     ├── GeminiCloudInterpreter
        │     ├── GeminiNanoInterpreter (AICore)
        │     ├── LocalLlmInterpreter  (MediaPipe LLM, cached sessions)
        │     └── IntentDecisionMap    (L1→L2→L3 orchestrator "Triple AI Brain")
        │
        ├── IntentRouter → IntentHandler implementations:
        │     ├── SearchIntentHandler   (search domain, TTS results)
        │     ├── AudioIntentHandler    (play/pause/stop/next/prev)
        │     ├── NavigationIntentHandler (geo:, Waze, GMaps)
        │     ├── SystemIntentHandler   (volume, wifi, bt, gps)
        │     ├── MessagingIntentHandler
        │     └── GenericLaunchHandler  (custom domains)
        │
        ├── MainViewModel (command processing + queue)
        └── ModelManagementViewModel
```

## 2. Voice Pipeline (runtime flow)

```
WakeWordService (foreground service, START_STICKY)
   │  owns IWakeWordEngine:
   │    ├── WakeWordEngine (Vosk + template DTW + voice print)
   │    ├── PorcupineWakeWordEngine
   │    └── OpenWakeWordEngine
   │
   ▼ wake word detected
onWakeWordDetected()
   ├── ConversationHandler.handleBargeIn()  (stop TTS if speaking)
   ├── wakeWordEngine.stopListening()       (release mic)
   ├── appStateManager.onWakeWordDetected() (sets wakeWordDetected=true)
   └── playHapticFeedback()
   │
   ▼ (reactive) WakeWordService collector on uiState.wakeWordDetected
MainViewModel.processVoiceCommand()  — or enqueueVoiceCommand() if PROCESSING
   │
   ▼
VoiceManager.startListening(lang, processor, onResult)
   ├── guard: isListening || _isListeningFlow.value
   ├── requestListeningAudioFocus()  → AUDIOFOCUS_GAIN_TRANSIENT (pauses music)
   ├── Google path: SpeechRecognizer (system STT)
   └── Local path: AudioRecord loop → silence detection → Whisper/Vosk transcribe
   │
   ▼ transcription result
MainViewModel (viewModelScope.launch)
   ├── setVoiceState(PROCESSING)
   ├── assistantEngine.processCommand(text)   ← IntentDecisionMap L1→L2→L3
   ├── intentRouter.route(NluIntent)          ← on Dispatchers.IO
   └── drainQueueOrIdle()                     ← queued commands or IDLE
   │
   ▼ IDLE
WakeWordService.handleVoiceStateChange(IDLE)
   └── delay(1500) cooldown → wakeWordEngine.startListening()  (resume WW)
```

## 3. VoiceState Machine

```
enum VoiceState: IDLE, LISTENING_WAKEWORD, LISTENING_COMMAND,
                 PROCESSING, CLEANING, BENCHMARKING

IDLE ──(WW service starts)──► LISTENING_WAKEWORD
LISTENING_WAKEWORD ──(wake word)──► LISTENING_COMMAND
LISTENING_COMMAND ──(transcription done)──► PROCESSING
PROCESSING ──(intent routed / queue drained)──► IDLE
IDLE ──(1.5s cooldown)──► LISTENING_WAKEWORD (WW restart)

any ──(engine change)──► CLEANING ──► IDLE
any ──(benchmark)──► BENCHMARKING
```

State transitions are **side-effect driven** via `WakeWordService.handleVoiceStateChange()`:
- `LISTENING_COMMAND` → stops WW engine (frees mic)
- `PROCESSING` → WW stays active if queue/TTS enabled (barge-in), else stops
- `IDLE` → restarts WW after 1500ms cooldown, with 2s retry on failure

## 4. Settings & State Handling

### Layers
1. **Persistence:** `DataStore<Preferences>` + `EncryptedSharedPreferences` (API keys)
2. **Repository:** `SettingsRepositoryImpl`
   - `settingsFlow: Flow<AppSettings>` — reactive read (maps every pref key)
   - `getSettingsSnapshot()` — **`runBlocking { settingsFlow.first() }`** synchronous read
   - Individual suspend setters
3. **State Hub:** `AppStateManager` (process-wide singleton via `getInstance`)
   - `combine(repo.settingsFlow, RemoteModelRegistry.modelMap, _runtimeState)` → `AppState`
   - Runtime ephemeral state (voiceState, wakeWordDetected, permissions) in separate `_runtimeState`
   - All setters are fire-and-forget: `scope.launch { repo.setX() }`
   - `voiceMutex: Mutex` — `executeSecureVoiceAction {}` serializes native resource access (Vosk/Whisper)
4. **Derived state:** `AppState.fromAppSettings()` computes `voiceModelReady`, `intentModelReady`, permissions

### Consumers
- **UI (Compose):** collects `appStateManager.uiState`
- **VoiceManager:** observes `uiState` → reinitializes STT engines on processor change
- **TtsManager:** observes `uiState` → tracks rate/pitch/focus mode
- **WakeWordService:** observes `uiState` → voice state side effects, wake word trigger, notification

### Remote config (JSON-driven)
- `RemoteModelRegistry` — models.json from `modelRepoBaseUrl` (configurable)
- `SearchProviderRegistry` — search_definitions.json (same base URL)
- Prompts (`standard_nlu`) also come from models.json
- `IntentTaxonomy` — **hardcoded Kotlin** (domains/actions), injected into LLM prompts

## 5. Intent Processing (Triple AI Brain)

```
IntentDecisionMap.processCommand(text)
  L1: FastMapEngine (regex rules from Room DB) — instant, offline
  L2: primary processor (OpenAI / Gemini Cloud / Gemini Nano / local LLM)
  L3: fallback processor (offline local LLM etc.)
Result: NluIntent(domain, action, actionVerb, logicalSubject, targetApp, confidence, …)
  │
IntentRouter.route()
  ├── AppResolver.resolve() — explicit app > user default > registry fallback
  └── first handler where canHandle(intent) → execute()
```

## 6. Audio Focus Management (3 actors)

| Actor | Focus type | When |
|---|---|---|
| `VoiceManager` | `GAIN_TRANSIENT` (pause music) | while listening for command |
| `TtsManager` | `GAIN_TRANSIENT` or `MAY_DUCK` (setting) | while speaking |
| `WakeWordEngine` | none (comment says "keep focus") | — |

## 7. Coroutine Scopes

| Component | Scope | Dispatcher |
|---|---|---|
| `AppStateManager` | `SupervisorJob + Main.immediate` | Main |
| `VoiceManager` (object) | `SupervisorJob + Main` | recording on IO |
| `WakeWordService` | `SupervisorJob + Main` | engine init on Main, loops on IO |
| `WakeWordEngine.listenLoop` | **new `CoroutineScope(Dispatchers.IO)` per start** | IO |
| `MainViewModel` | `viewModelScope` | route on IO |

---

# PART 2 — POTENTIAL PROBLEMS & RACE CONDITIONS

## A. Confirmed / High Risk

### A1. `WakeWordService.serviceScope` never cancelled ⚠️
`onDestroy()` does **not** call `serviceScope.cancel()`. With `START_STICKY`, the service can be killed/recreated; every `onCreate()` launches 4 new collectors (voice state, profile, overlay visibility, background trigger) — **the old ones leak and keep running**. The logs show duplicate/triple "Background trigger activated!" and double "Overlay view added" — consistent with leaked collectors from previous service instances. This was the root cause of the triple `startListening` calls (mitigated by the listening guard, but the leak remains).

**Fix:** cancel `serviceScope` in `onDestroy()`; alternatively make collectors idempotent.

### A2. `getSettingsSnapshot()` uses `runBlocking` on hot paths
Called from: notification building, wake-word match check (`isValidWakeWordMatch` — per partial result!), silence-threshold read (`getSilenceThreshold` — per audio buffer!), prompt building, etc. Each call re-reads and re-maps the **entire** DataStore. On the audio thread this means file I/O every ~100ms buffer. Risk: audio underruns, jank, ANR if Main thread hits it.

**Fix:** cache the snapshot (already have `uiState.value` in AppStateManager — read from it instead).

### A3. `wakeWordDetected` trigger uses a boolean + delay(500) reset
```
onWakeWordDetected → wakeWordDetected=true → collector fires → delay(500) → reset false
```
- If a second wake word arrives **within 500ms**, `distinctUntilChanged` swallows it.
- If `uiState` recombines for any unrelated settings change while `true`, and a leaked collector (A1) also observes it → duplicate triggers.
- A boolean in global state as an **event** is fragile; should be a `SharedFlow` event or a counter.

### A4. Fire-and-forget settings writes → read-after-write races
All `AppStateManager.setX()` are `scope.launch { repo.setX() }`. A caller that immediately reads `getSettingsSnapshot()` (or `uiState.value`) can observe stale data. Example: `setVoiceProcessor()` writes processor, then reads snapshot for model selection **inside the same launch** — OK there, but external callers (UI chains, VoiceManager reinit trigger) can interleave.

### A5. Double command-processing path race (queue)
`WakeWordService` decides `processVoiceCommand` vs `enqueueVoiceCommand` by reading `uiState.value.voiceState` — but state may change between read and call (PROCESSING may have just ended). Worst case: command silently dropped ("queue disabled — ignoring") or two `startListening` calls racing the guard. The guard in `VoiceManager.startListening` is check-then-act **without a lock** (`if (isListening || flow.value)` then set later); two threads/coroutines can both pass the check.

**Fix:** make the guard atomic (`AtomicBoolean.compareAndSet` or Mutex).

### A6. `VoiceManager.reinitializeEngines` releases engines mid-flight
`startProcessorObservation` uses `collectLatest` → any engine-related change during active listening calls `release()` which kills the recording loop, destroys `speechRecognizer`, etc., **without abandoning audio focus** taken by the current session (release→stopListening does abandon — OK, but state transitions during PROCESSING can conflict: `CLEANING` overrides `PROCESSING` and the MainViewModel `finally` block will then set IDLE, fighting the reinit).

## B. Medium Risk

### B1. Two `AppStateManager` construction paths but shared singleton — different `SettingsRepositoryImpl` instances
`WakeWordService.onCreate` does `SettingsRepositoryImpl(this)` (new instance) and `AppStateManager.getInstance(repo, this)`. Since the singleton already exists (created by AppContainer), the **new repo is discarded** for state but **kept locally** in the service (`settingsRepo`). Two `SettingsRepositoryImpl` instances exist, but **verified safe**: `DataStoreProvider` uses a top-level `preferencesDataStore` delegate on `applicationContext`, so there is exactly one DataStore per process. Remaining concern: the service holds a separate repo reference — harmless but confusing; also two `VoiceOverlayManager` instances exist (one in `AppContainer`, one in `WakeWordService`).

### B2. `WakeWordEngine.listenLoop` launched in unowned scope
`CoroutineScope(Dispatchers.IO).launch { listenLoop() }` — no parent job, cannot be cancelled structurally; only exits via `isListening` flag. If `stopListening()` races `startListening()`, two loops could briefly overlap reading the same `audioRecord` reference (one sees null → break, usually OK, but not guaranteed).

### B3. TTS audio focus vs Listening audio focus interplay
`VoiceManager` requests focus (music pauses), then command routes to `SearchIntentHandler` → TTS speaks → TtsManager requests its own focus, then abandons. If VoiceManager's abandon and TtsManager's request interleave wrongly, music may resume mid-TTS or stay paused. Three actors + Spotify's own focus handling = fragile ordering.

### B4. `AudioIntentHandler.playSearch` uses `Thread.sleep(3000)` on caller thread
Runs on `Dispatchers.IO` via router — blocks an IO thread for 3s, plus `runBlocking` for Piped search inside. Works but wasteful; also delays queue drain.

### B5. `handleVoiceStateChange` cooldown races
IDLE → delay(1500) → re-check state → start WW. If user triggers wake word via UI mic button during the cooldown, `LISTENING_COMMAND` handler calls `wakeWordEngine?.stopListening()` — fine — but the delayed coroutine's second check passes only on state; a rapid IDLE→LISTENING→IDLE sequence can spawn **two** delayed restarts (collectLatest cancels previous — actually OK here since `collectLatest` on uiState cancels; but the *nested* `serviceScope.launch` inside is **not** cancelled by collectLatest!). The inner `launch` escapes `collectLatest` cancellation → duplicate WW restarts possible.

### B6. Command queue lost on process death; no max size
`commandQueue` is in-memory in MainViewModel; unbounded growth if AI is slow and user keeps triggering.

### B7. `IntentDecisionMap` mixes `isCloudIntelligenceEnabled` gating with processor fallbacks
If primary=OpenAI but cloud disabled → returns null → falls to L3; but if fallback is also cloud, it's called **without** the gate check in the fallback branch (`Strings.AiProcessors.OPENAI -> l2CloudEngine.processCommand(...)` — no `isCloudIntelligenceEnabled` check).

## C. Lower Risk / Code Health

### C1. `AppState.fromAppSettings` runs file I/O (`File.exists()`) inside the combine on every emission (permissions checks too) — Main.immediate dispatcher.

### C2. `refreshNativeLibsStatus()` runs on every uiState emission with `getSettingsSnapshot()` (runBlocking) + file checks — amplifies A2.

### C3. Overlay double-adds — logs show "Overlay view added to WindowManager successfully" twice back-to-back. **Verified:** `VoiceOverlayManager.show()` HAS an "already shown" guard (`composeView != null` check), so the double log means two different `VoiceOverlayManager` instances are showing — caused by leaked collectors from a previous service instance (A1), each with its own manager instance.

### C4. `VoiceManager` is a singleton `object` holding `context` — uses `applicationContext`, OK, but engine references are set both from `init()` params (now nulls) and internal reinit; dead parameters left in `init` signature.

### C5. Hardcoded language list in `AppState` (`listOf("en", "ro", "de", "fr")`) for custom Vosk paths — inconsistent with JSON-driven translations.

### C6. `stopVoiceCommand()` doesn't clear the command queue — after manual stop, queued commands may still fire later via drain (actually drain runs only in `finally`; if user stops mid-listen, callback never fires and queue entries stay stale).

### C7. Legacy `IntentPayload` still coexists with `NluIntent` (`domain/intent/model/IntentPayload.kt`) — migration debt.

### C8. `SearchIntentHandler.execute` returns `true` before async search completes — router logs success regardless of outcome.

---

# Suggested Priorities

1. **A1** — cancel `serviceScope` in `WakeWordService.onDestroy()` (1 line; fixes duplicate triggers/overlays at the root).
2. **A2/C2** — replace hot-path `getSettingsSnapshot()` with `appStateManager.uiState.value` reads.
3. **A5** — atomic listening guard (`AtomicBoolean.compareAndSet`).
4. **A3** — convert wake-word trigger from state boolean to `MutableSharedFlow<Unit>` event.
5. **B5** — hoist the nested restart `launch` so `collectLatest` cancellation applies.
