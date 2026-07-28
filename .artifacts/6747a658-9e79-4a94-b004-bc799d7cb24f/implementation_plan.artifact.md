# Plan de Implementare: Audit Complet și Corectare Documentație

Acest plan vizează eliminarea tuturor erorilor de terminologie ("llama.cpp") și clarificarea arhitecturii "Triple AI Brain" în `README.md` și `docs/TECHNICAL_DOCUMENTATION.md`, conform implementării reale din cod.

## Rezumat Audit (Cod vs. Doc)

| Componentă | Stare în Cod | Eroare în Doc (Găsită) | Corecție |
|---|---|---|---|
| **NLU L1** | Regex (FastMap) | OK | Rămâne neschimbat. |
| **NLU L2** | Engine-ul primar ales de user (Cloud sau Local). | Menționa `llama.cpp` | Se va numi **"MediaPipe GenAI"** (pentru modele locale) sau **"Gemini/OpenAI"** (cloud). |
| **NLU L3** | Mecanism de tip "Retry" cu un alt engine pre-configurat. | Menționa MediaPipe ca strat separat | Clarificare: L3 este un **mecanism de fallback**, nu o tehnologie separată. |
| **Local LLM** | `com.google.mediapipe.tasks.genai` | `llama.cpp` | Eliminare orice referință la llama. |

## Schimbări Propuse

### 1. [MODIFY] [TECHNICAL_DOCUMENTATION.md](file:///Users/swimnobody/StudioProjects/VoxApps/docs/TECHNICAL_DOCUMENTATION.md)
- **Secțiunea 4 (NLU)**: Redefinirea L2 și L3. L2 este "Primary Attempt", L3 este "Fallback Attempt".
- **Tabelul de Engine-uri**: Înlocuirea "Local LLM (llama.cpp)" cu **"On-device LLM (MediaPipe GenAI)"**.
- **Secțiunea 18 (Dependencies)**: Eliminarea label-ului "(llama.cpp)".

### 2. [MODIFY] [README.md](file:///Users/swimnobody/StudioProjects/VoxApps/README.md)
- **Features**: Clarificarea NLU: "Redundant pipeline (L1-L2-L3) with instant regex and hybrid local/cloud LLMs."
- **Key Technologies**: Înlocuirea `llama.cpp` cu **MediaPipe GenAI**.

### 3. [MODIFY] [XDA_POSTS_ACCURATE.artifact.md](file:///Users/swimnobody/StudioProjects/VoxApps/.artifacts/6747a658-9e79-4a94-b004-bc799d7cb24f/XDA_POSTS_ACCURATE.artifact.md)
- Eliminarea referințelor la `llama.cpp` din descrierea "Triple AI Brain".

## User Review Required

> [!NOTE]
> Am confirmat în `LocalLlmInterpreter.kt` că folosim exclusiv SDK-ul oficial **MediaPipe GenAI** de la Google pentru rularea modelelor locale `.bin`. Nu există nicio urmă de `llama.cpp` în codul tău.

## Plan de Verificare

1. **Grep global**: `grep -r "llama" .` (trebuie să returneze 0 rezultate în afara folderului `.git`).
2. **Review logică L3**: Recitirea descrierii L3 pentru a ne asigura că e prezentat ca un mecanism de siguranță, nu ca un engine fix.
