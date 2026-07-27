# Plan de Implementare: Corectare Mesaj "100% On-Device" & Transparență

Acest plan vizează alinierea tuturor materialelor publice și a documentației cu realitatea tehnică a proiectului: VoxApps este **Local-First**, nu 100% Offline, deoarece oferă integrări opționale cu servicii cloud (OpenAI, Spotify, Rate de schimb, Căutare Web).

## Schimbări Propuse

### 1. Actualizare README.md (VoxApps)
Voi nuanța afirmațiile care pot fi considerate înșelătoare:
- Schimbare din "100% Private" în **"Private by Design / Local-First"**.
- Clarificare în introducere: "Core processing is local, with optional opt-in cloud enhancements (OpenAI, Spotify, etc.)".

### 2. Actualizare privacy.html (vox-fdroid-repo)
Voi corecta "Core Privacy Principle" pentru a fi 100% transparent:
- Menționarea explicită a **ratelor de schimb valutar** (Currency Exchange Rates) ca fiind descărcate din cloud.
- Menționarea explicită a **Search Providers** (Weather, News) ca fiind apeluri cloud.
- Reformularea secțiunii "Core Principle" din "100% On-Device" în **"Privacy-First & Local-Processing Architecture"**.

### 3. Actualizare Postări XDA (BBCode)
Voi trece prin toate postările generate anterior și voi înlocui orice mențiune de "100% Private" sau "100% On-Device" cu termeni care reflectă natura hibridă, subliniind că datele sunt stocate local, dar anumite inteligențe pot fi cloud-based.

## User Review Required

> [!WARNING]
> **Riscuri Google Play**: Dacă spunem în descriere "100% Offline" și Google vede în cod apeluri către `api.openai.com` sau `api.spotify.com`, aplicația va fi suspendată pentru "Deceptive Behavior". Este vital să folosim limbajul corect: **"Local-first architecture with optional cloud features"**.

## Plan de Verificare

1. **Inspecție vizuală README**: Verificarea noilor paragrafe de avertizare.
2. **Inspecție Privacy Policy**: Asigurarea că exchange rates și search sunt listate.
3. **Audit XDA**: Verificarea tuturor celor 6 postări.
