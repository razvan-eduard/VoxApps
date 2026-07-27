# XDA Forum Posts (Updated for Transparency)

Use these BBCode blocks for your XDA presentation. They have been updated to reflect the **Local-First** nature of the apps while acknowledging optional cloud features.

---

### [POST 1] Welcome to VoxApps & Vox Commander Deep-Dive

```bbcode
[CENTER]
[IMG]https://raw.githubusercontent.com/razvan-eduard/VoxApps/main/docs/assets/voxapps-banner.webp[/IMG]

[SIZE=7][B][COLOR=#6200ee]VoxApps[/COLOR][/B][/SIZE]
[SIZE=4][I]Private by Design: The Local-First Android Ecosystem[/I][/SIZE]

[QUOTE]
[B]Tired of Cloud-only Assistants?[/B] VoxApps is a suite of independent, encrypted apps designed to work offline. While core processing and storage stay on your hardware, you can optionally augment the experience with cloud intelligence like OpenAI or Spotify.
[/QUOTE]

[SIZE=5][B]The Monorepo Overview[/B][/SIZE]
VoxApps is a micro-OS for offline plugins. Install only what you need:
[SIZE=2]
🎙️ [B]voxCommander()[/B] - The local-first brain. Voice recognition & intent routing.
📝 [B]voxNotes()[/B] - Encrypted notes with local AI cleanup.
📷 [B]voxVision()[/B] - Document scanner with on-device OCR.
💸 [B]voxExpenses()[/B] - Secure tracker with local bank notification capture.
📅 [B]voxCalendar()[/B] - Natural language scheduling & layered views.
🗂️ [B]voxHub()[/B] - P2P Bluetooth sync. Zero Cloud required for sync.
[/SIZE]

[SIZE=6][COLOR=#03dac6][B]🎙️ Vox Commander: The Hybrid Engine[/B][/COLOR][/SIZE]

[TABLE]
[TR]
[TD][IMG]https://xdaforums.com/attachments/1_commander_home-jpeg.6367430/[/IMG][/TD]
[/TR]
[/TABLE]
[I][SIZE=1]Local voice overlay - Private by Default[/SIZE][/I]

[LEFT]
Vox Commander manages the wake word, speech-to-text, and intent routing locally. It acts as the coordinator for the entire ecosystem.

[B]Triple AI Brain (NLU)[/B]
[LIST]
[*][B]L1: FastMap[/B] - Instant local regex matching.
[*][B]L2: Primary AI[/B] - Gemini Nano (Local) or OpenAI (Cloud) for complex intents.
[*][B]L3: Offline Fallback[/B] - MediaPipe GenAI ensuring reliability without network.
[/LIST]

[B]Voice Stack[/B]
[LIST]
[*][B]Wake Word:[/B] Vosk, Porcupine, or OpenWakeWord (All local).
[*][B]STT:[/B] Whisper.cpp (On-device multilingual support).
[*][B]TTS:[/B] Piper TTS (High-quality neural voices, 100% offline).
[/LIST]
[/LEFT]

[B][SIZE=4]Commander Interface Gallery[/SIZE][/B]
[TABLE]
[TR]
[TD][IMG]https://xdaforums.com/attachments/6-jpeg.6367432/[/IMG][/TD]
[TD][IMG]https://xdaforums.com/attachments/9-jpeg.6367433/[/IMG][/TD]
[TD][IMG]https://xdaforums.com/attachments/12-jpeg.6367438/[/IMG][/TD]
[TD][IMG]https://xdaforums.com/attachments/10-jpeg.6367439/[/IMG][/TD]
[/TR]
[/TABLE]
[/CENTER]
```

---

### [POST 2] The Satellite Apps: Intelligent Utility

```bbcode
[CENTER]
[SIZE=6][COLOR=#ff0266][B]🚀 The Satellites: Smart & Secure[/B][/COLOR][/SIZE]
[SIZE=3][I]Independent apps that plug into the local ecosystem.[/I][/SIZE]

[SIZE=5][COLOR=#c2185b][B]💸 Vox Expenses[/B][/COLOR][/SIZE]
[TABLE]
[TR]
[TD][IMG]https://xdaforums.com/attachments/1_expenses_list-jpeg.6367434/[/IMG][/TD]
[/TR]
[/TABLE]

[LEFT]
An encrypted tracker with [B]Local Processing[/B]. Log spending via voice, receipt scanning, or automatic bank notification capture.
[LIST]
[*][B]Durable Notification Queue:[/B] Captures are processed locally even if the app is stopped.
[*][B]Cloud-Augmented (Optional):[/B] Fetch exchange rates or use OpenAI for deep receipt parsing.
[*][B]Merchant Category Memory:[/B] Automatically learns your vendor-category mappings offline.
[/LIST]
[/LEFT]

[B][SIZE=4]Expenses View Gallery[/SIZE][/B]
[TABLE]
[TR]
[TD][IMG]https://xdaforums.com/attachments/3-jpeg.6367435/[/IMG][/TD]
[TD][IMG]https://xdaforums.com/attachments/4-jpeg.6367436/[/IMG][/TD]
[TD][IMG]https://xdaforums.com/attachments/16-jpeg.6367437/[/IMG][/TD]
[TD][IMG]https://xdaforums.com/attachments/9-jpeg.6367440/[/IMG][/TD]
[/TR]
[/TABLE]

[SIZE=5][COLOR=#c2185b][B]📝 Vox Notes[/B][/COLOR][/SIZE]
[LEFT]
Privacy-first notes with [B]SQLCipher encryption[/B].
[LIST]
[*][B]Smart Categories:[/B] Visual coverflow-style picker with inline creation.
[*][B]Local AI Cleanup:[/B] Merges near-duplicate categories using on-device models.
[*][B]Scan-to-Note:[/B] Uses [B]Local OCR[/B] to title and categorize documents.
[/LIST]
[/LEFT]

[SIZE=5][COLOR=#c2185b][B]📅 Vox Calendar[/B][/COLOR][/SIZE]
[LEFT]
Combines a powerful agenda with natural language simplicity.
[LIST]
[*][B]Natural Language Creation:[/B] Local date resolution for commands like "dentist in a week."
[*][B]Cross-App Day Linking:[/B] Tapping a day shows your [B]Notes and Expenses[/B] locally inline.
[*][B]Standard Interop:[/B] ICS import/export for Google/Apple Calendar.
[/LIST]
[/LEFT]

[B][SIZE=4]Calendar & Notes Gallery[/SIZE][/B]
[TABLE]
[TR]
[TD][IMG]https://xdaforums.com/attachments/9-jpeg.6367440/[/IMG][/TD]
[TD][IMG]https://xdaforums.com/attachments/9-jpeg.6367433/[/IMG][/TD]
[/TR]
[/TABLE]
[/CENTER]
```

---

### [POST 3] Privacy, Sync & Tech Specs

```bbcode
[CENTER]
[SIZE=6][COLOR=#ffab00][B]🔐 Privacy & Security[/B][/COLOR][/SIZE]

[B]🛡️ Local-First. Private by Design.[/B]
Your primary data never leaves your device unless you explicitly enable cloud features. Encryption is handled via [B]SQLCipher[/B] and [B]Android Keystore[/B].

---

[SIZE=5][COLOR=#e65100][B]🗂️ Vox Hub & P2P Sync[/B][/COLOR][/SIZE]
Pair two phones with an [B]NFC tap[/B] to exchange AES-256 keys, then sync bidirectionally via [B]Bluetooth Classic[/B]. No cloud middlemen, no servers, just your devices talking to each other.

[B]The Tech Stack[/B]
[TABLE]
[TR]
[TD][B]Component[/B][/TD]
[TD][B]Technology[/B][/TD]
[/TR]
[TR]
[TD]Intelligence[/TD]
[TD]Whisper.cpp, Vosk, MediaPipe, PaddleOCR[/TD]
[/TR]
[TR]
[TD]Hybrid Core[/TD]
[TD]Local Processing + Optional OpenAI/Spotify API[/TD]
[/TR]
[TR]
[TD]Storage[/TD]
[TD]SQLCipher (Encrypted Room DB)[/TD]
[/TR]
[/TABLE]
[/CENTER]
```
