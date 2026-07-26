# Walkthrough: Sincronizare Changelog F-Droid și Trigger Build

Am implementat automatizarea pentru sincronizarea changelog-urilor către F-Droid și am declanșat build-uri noi pentru toate aplicațiile VoxApps.

## Modificări Efectuate

### 1. Sincronizare Metadate F-Droid
- **Script nou**: [sync_fdroid_metadata.sh](file:///Users/swimnobody/StudioProjects/VoxApps/scripts/sync_fdroid_metadata.sh)
    - Preia automat corpul release-urilor din GitHub folosind CLI-ul `gh`.
    - Creează structura de directoare `metadata/<appid>/en-US/changelogs/<versionCode>.txt`.
- **Workflow nou**: [deploy-fdroid.yml](file:///Users/swimnobody/StudioProjects/VoxApps/.github/workflows/deploy-fdroid.yml)
    - Se declanșează automat la finalizarea oricărui workflow de release.
    - Sincronizează metadatele generate în repository-ul [vox-fdroid-repo](https://github.com/razvan-eduard/vox-fdroid-repo).

### 2. Declanșare Build-uri APK (Re-build)
Am incrementat `versionCode` în toate aplicațiile pentru a forța GitHub Actions să genereze APK-uri noi. Acest lucru va activa și corecția de semnătură `vox-apps` (pentru Commander) făcută anterior.

| Aplicație | Vechiul `versionCode` | Noul `versionCode` |
|---|---|---|
| **Vox Commander** | 9 | 10 |
| **Vox Notes** | 13 | 14 |
| **Vox Expenses** | 15 | 16 |
| **Vox Calendar** | 9 | 10 |
| **Vox Hub** | 9 | 10 |
| **Vox Vision** | 11 | 12 |

## Verificare CI
Puteți urmări progresul build-urilor aici: [GitHub Actions - VoxApps](https://github.com/razvan-eduard/VoxApps/actions)

> [!IMPORTANT]
> **Secret necesar**: Workflow-ul de deploy F-Droid va încerca să folosească un secret numit `FDROID_REPO_TOKEN` pentru a împinge schimbările în repo-ul de F-Droid. Dacă acesta nu este setat, va folosi `GITHUB_TOKEN`, care s-ar putea să nu aibă permisiuni de scriere în alt repository.

> [!TIP]
> Odată ce build-urile sunt gata, utilizatorii F-Droid vor vedea acum textul din GitHub Release direct în secțiunea "What's New" a aplicației F-Droid.
