# Plan de Implementare: Tranziție Licență de la MIT la GPLv3

Acest plan detaliază pașii necesari pentru a schimba licența întregului proiect VoxApps din MIT în GNU General Public License v3.0 (GPLv3).

## Analiză de Compatibilitate

După scanarea dependențelor, am concluzionat că trecerea la GPLv3 este nu doar posibilă, ci și **recomandată pentru coerență legală**:

1.  **NewPipeExtractor**: Această bibliotecă (folosită pentru YouTube) este deja sub licență **GPLv3**. Folosirea unei biblioteci GPLv3 într-un proiect MIT poate crea zone gri legale; trecerea întregului proiect la GPLv3 elimină orice ambiguitate.
2.  **Dependențe MIT/Apache 2.0 (Whisper, OpenCV, Vosk)**: Aceste licențe sunt "permisive" și sunt 100% compatibile cu GPLv3. Poți include cod MIT/Apache într-un proiect GPLv3 fără probleme.
3.  **Codul tău (VoxApps)**: Ca proprietar al drepturilor de autor, ai libertatea deplină de a schimba licența codului tău de la MIT la GPLv3.

## Schimbări Propuse

### 1. [MODIFY] `LICENSE` (Root)
Înlocuirea textului actual MIT cu textul complet al licenței **GNU GPL v3.0**.

### 2. [MODIFY] `README.md`
Actualizarea secțiunii "License" pentru a reflecta noua licență și a explica faptul că aceasta oferă o protecție mai puternică împotriva închiderii codului în produse proprietare.

### 3. [MODIFY] Metadate F-Droid & XDA
Actualizarea fișierelor de metadate și a postărilor de pe forum pentru a schimba eticheta din "MIT" în "GPL-3.0-only".

## User Review Required

> [!WARNING]
> **Efectul GPLv3**: Aceasta este o licență "Strong Copyleft". Înseamnă că oricine folosește codul tău într-o aplicație distribuită este **obligat** să facă la rândul său codul sursă public sub aceeași licență. Este o schimbare majoră de filosofie față de MIT.

## Plan de Execuție

- [ ] Descărcarea și scrierea textului oficial GPLv3 în fișierul `LICENSE`.
- [ ] Actualizarea header-ului din `README.md`.
- [ ] Actualizarea `privacy.html` și a postărilor XDA (dacă menționează licența).
- [ ] Verificare finală: Scanarea tuturor fișierelor pentru a nu lăsa referințe la "MIT" vechi.
