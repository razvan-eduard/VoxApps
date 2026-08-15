# Build & Release Guide

How to build APKs locally, what runs on every push, how the per-app GitHub Actions release workflows
work, how to publish without bumping a version, and how fastlane screenshots are captured. For
vendored native-library build steps (Whisper.cpp, Vosk, OpenCV, etc.) see
`BUILD_TIME_DEPENDENCIES.md` instead — this doc is about the app APKs themselves.

## Local builds

**Debug** (emulator-only — see the release-only install rule below):

```
./gradlew :vox-<app>:assembleDebug
```

**Signed release** (installable on a real device without wiping its signed data):

```
cd /Users/swimnobody/StudioProjects/VoxApps
export RELEASE_KEYSTORE_PATH=~/.voxapps/voxapps-release.jks
export RELEASE_KEYSTORE_PASSWORD=$(cat ~/.voxapps/keystore_password.txt | tr -d '[:space:]')
./gradlew :vox-<app>:assembleRelease --no-daemon
```

`<app>` is one of `vox-commander`, `vox-expenses`, `vox-calendar`, `vox-notes`, `vox-hub`,
`vox-vision`. Multiple `assembleRelease` targets can be listed in one invocation. Without the two
env vars set, `assembleRelease` still succeeds but produces an **unsigned** APK that can't be
installed over an existing signed install.

**Never install a debug APK over a real device's existing release install** — the signature
mismatch forces Android to uninstall first, wiping that app's local data. Debug APKs are fine on a
disposable emulator only.

### Where the keys live

All key material sits in `~/.voxapps/` (mode 700), outside the repository:

| File | What it signs |
|---|---|
| `voxapps-release.jks` | every app's APK — alias `vox-apps`, shared across the family so the signature-level IPC permissions match |
| `keystore_password.txt` | that keystore's password |
| `schema-signing.pem` | the schema manifest, so a fetched schema can change what an install does |

Outside the repository on purpose. `git clean -xfd` deletes ignored files, so a key kept in the
project folder — however well gitignored — is one routine cleanup away from gone, and losing the
schema key means an app release to embed a replacement.

`./scripts/vox release package` and `./scripts/vox schemas sign` both look here by default, so
neither needs arguments or environment variables.

**The schema key is deliberately not in GitHub.** The release keystore has to be — CI cannot build
signed APKs otherwise — and putting the schema key beside it would mean one account compromise
yields both: malicious schemas *and* a malicious signed APK. So schemas are signed on your machine
and CI only verifies (`verify-schemas.yml`). After editing anything under `remote-schemas/`:

```
./scripts/vox schemas sign      # then commit manifest.json and manifest.json.sig
```

When a schema gains or changes a downloadable model URL, run `./scripts/vox schemas hash-models`
before signing — every downloadable model URL carries a `sha256` beside it, and `./scripts/vox
test` fails when one is missing.

Forget, and the apps simply ignore the change — safe, but silent, which is why CI fails the check
rather than leaving it to be noticed.

### The APK that ships

`assembleRelease` **is** the published APK — set the keystore variables and Gradle signs it too:

```
export RELEASE_KEYSTORE_PATH=~/.voxapps/voxapps-release.jks
export RELEASE_KEYSTORE_PASSWORD=$(cat ~/.voxapps/keystore_password.txt | tr -d '[:space:]')
./gradlew :vox-commander:assembleRelease -PvoxDlc=full    # or omit -P for the default, minimal
```

Output: `vox-commander/build/outputs/apk/release/vox-commander-release.apk`, byte-for-byte the thing
`release-commander.yml` builds. `./scripts/vox release package` is the same command with the
keystore filled in from `~/.voxapps`.

AGP does the packaging, in both modes, for the APK and the bundle alike, so a locally built release
exercises the same DLC download path the published one does.

### How much ships inside the APK (`voxDlc`)

One property decides whether the native payload is bundled or downloaded, and it is **`minimal` by
default** (`gradle.properties`):

```
./gradlew :vox-commander:assembleRelease                    # minimal — the default
./gradlew :vox-commander:assembleRelease -PvoxDlc=full      # smaller APK, libs fetched on the splash
```

| | `minimal` (default) | `full` |
|---|---|---|
| Commander APK | ~38 MB, 9 libs inside (libllama.so among them) | ~24 MB, 5 libs inside |
| Vision APK | ~61 MB, 15 libs inside | ~16 MB, 5 libs inside |
| First launch | nothing downloads; works offline | 33 MB (Commander) / 43 MB (Vision) fetched on the splash |
| Whisper | on demand, unchanged | on demand, unchanged |
| llama.cpp runtime | inside the APK | fetched on first use |

**Whisper is unaffected by the switch.** It is the genuinely optional payload — ~27 MB
(libwhisper.so + libomp.so, hybrid CPU+OpenCL) excluded by AGP in both modes and fetched only if
you choose Whisper STT. The llama.cpp runtime is governed by the switch: `minimal` compiles
libllama.so (~6 MB, hybrid CPU+OpenCL) from source into the APK, where `LlamaEngineManager` finds
it in `nativeLibraryDir` and never downloads — an offline-ready engine with no runtime-download
trust gap; `full` excludes it and fetches it on demand from its pinned release when a local LLM
engine is selected. Each engine's GPU use is a separate opt-in toggle, proven per device by a
sandboxed compatibility probe (see `TECHNICAL_DOCUMENTATION.md`). Everything the switch does govern is mandatory: the app cannot run
without those libraries, so in `full` they are a required download on the splash, which is why
`minimal` is the default.

The property reaches the app through `BuildConfig.DLC_MODE`, which is what `NativeLibs` reads to
decide whether to fetch anything. **That is deliberate and load-bearing**: the packaging decision and
the download decision have to be the same decision. Build one way and package the other and you ship
an APK missing libraries nothing will ever fetch.

**`gradle.properties` is the only place the mode is written.** The release workflows read it in a
"Resolve DLC mode" step and use that answer both for the build and for whether to attach the `.so`
assets. A dispatched run can override it for that run through the `dlc_mode` input; blank means
"whatever `gradle.properties` says".

An invalid value fails the build rather than silently choosing:

```
voxDlc must be 'minimal' or 'full', got 'nonsense'
```

**Both apps exclude the same way** — AGP's `packaging.jniLibs.excludes`, applied per variant. They
differ in where the excluded libraries come from: Vision's are files in
`vox-vision/src/main/jniLibs/`, so they are on disk for upload no matter how the APK is packaged,
while Commander's come from AAR dependencies and are excluded before any build output contains
them. `:vox-commander:collectDlcLibs` therefore stages them straight from
the resolved dependencies; it runs automatically after `assembleRelease` in `full`, and writes to
`vox-commander/build/dlc-libs/`.

Two artifacts provide a `libonnxruntime.so`: sherpa-onnx's own (~21 MB) and onnxruntime-android's
(~28 MB). They are different binaries, and only sherpa's exports the symbol version
`libsherpa-onnx-jni.so` needs. `collectDlcLibs` picks by artifact rather than by file name, and
fails the build if the choice is ever ambiguous.

### Skipping the native prep

`vox-commander`'s `preBuild` attaches two compiles — whisper.cpp and llama.cpp, each from its
submodule — the only scripts attached to a build, and both produce output the app links. The
upstream-version checks belong to the weekly sync bots and are not wired into any build.

```
./gradlew :vox-commander:assembleDebug -PvoxSkipNativePrep
```

skips the whisper and llama compiles for a build that only needs to know whether the Kotlin compiles.
Everything downstream of `preBuild` still runs. `copyShippedSchemas` is **not** skippable — the
schema tests read the assets it generates.

Both native compiles produce hybrid CPU+OpenCL libraries with Adreno-tuned kernels embedded in the
binary. The GPU inputs are repo-pinned — the Khronos headers are the `vendor/OpenCL-Headers`
submodule and the import library is a static dlopen shim built per build tree from
`vox-commander/src/main/cpp/opencl-shim/` — so a build machine needs only the SDK/NDK/CMake and
python3 (kernel embedding), no host GPU packages. The CI workflows that compile an engine set up
the SDK with `android-actions/setup-android` so `sdkmanager` is on PATH, install NDK+CMake, and
check out `vendor/OpenCL-Headers`.

### What a release does before it publishes

Between building the artifacts and creating the release, each `release-*.yml` runs these steps, in
this order:

| step | what it establishes |
|---|---|
| `apksigner verify` | the APK is signed, with the certificate every previous release used |
| digest assets inside the APK | *(Commander)* `assets/whisper-libs.sha256`/`.commit` and `assets/llama-libs.sha256`/`.commit` are packaged, and the recorded whisper digests equal what the published `whisper-libs` release serves — the digests describe what an install downloads, not what this runner compiled; in `full`, Commander and Vision also require `assets/dlc-libs.sha256` |
| `vox check smoke <apk> <app-id>` | the APK installs and survives a cold launch on an x86_64 emulator, the arm64-v8a libraries running under the image's ARM binary translation — all six workflows |
| `vox release sbom <app>` | a CycloneDX SBOM — the resolved dependency graph, plus the vendored sources this app compiles, recorded by the commit each submodule is pinned to |
| `vox check whisper-published` | *(Commander)* the release the app will fetch its Whisper runtime from exists, so an install cannot 404 on libraries its APK expects ([detail](BUILD_TIME_DEPENDENCIES.md#addressing-the-runtime-by-the-commit-it-was-built-from)) |
| `vox check llama-published` | *(Commander)* the release the app fetches `libllama.so` from exists, addressed by the llama build fingerprint ([detail](BUILD_TIME_DEPENDENCIES.md#pattern-b-llamacpp-vendored-unmodified-compiled-at-build-time)) |
| `vox check pairing` | every native library in the APK satisfies what its dependants were built against ([detail](BUILD_TIME_DEPENDENCIES.md#do-the-native-libraries-satisfy-each-other)) |
| `actions/attest-build-provenance` | signed provenance over the APK, AAB and SBOM, recorded in GitHub's transparency log |

A failure in any of them stops the run before anything is tagged, uploaded or attested. Commander and
Vision pass `--with-libs` in `full` mode so the check sees the libraries the device will download
rather than only what is inside the APK.

Anyone can check a downloaded APK against the attestation:

```
gh attestation verify VoxCommander-<tag>.apk --repo razvan-eduard/VoxApps
```

### Build hosts

macOS and Linux directly; Windows through WSL, since the scripts are bash. The Android SDK and NDK
are resolved from `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`ANDROID_NDK_HOME` and each platform's default
location, so no environment needs a particular layout — see
[BUILD_TIME_DEPENDENCIES.md](BUILD_TIME_DEPENDENCIES.md#where-the-toolchain-comes-from).

## How `main` is protected

A ruleset covers `refs/heads/main`:

| rule | effect |
|---|---|
| `deletion`, `non_fast_forward` | the branch cannot be deleted or force-pushed |
| `pull_request` | changes arrive as a PR; no approvals required |
| `required_status_checks` | `scripts` and `verify` must pass before a PR merges |

Repository admins bypass the first two rules and push to `main` directly. Everything that is not an
admin — Dependabot above all — goes through a PR that cannot merge until both checks are green.
Auto-merge is enabled, so a PR can be queued rather than watched:

```
gh pr create --fill && gh pr merge --auto --squash
```

`update-readme-releases.yml` regenerates the release table after a release and commits it. It pushes
with `README_PUSH_TOKEN`, a fine-grained token owned by an admin and scoped to `contents:write` on
this repository — `GITHUB_TOKEN` is not a bypass actor, and on a personal account the GitHub Actions
app cannot be named as one. A fork has no such secret and falls back to the default token, which its
own unprotected `main` accepts.

Both required checks come from `ci.yml`, which has no path filters, so they report on every PR. A
required check that can fail to *run* blocks a PR permanently instead of gating it, which is why the
path-filtered workflows (`validate-schemas`, `verify-schemas`, `verify-vendor-patches`) are not
required here.

What they cover is `./gradlew test` and `assembleDebug` — the unit tests and a debug build. Release
packaging, R8 and anything that only appears once the app is installed are outside them.

## What runs on every push

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci.yml` | every push to `main`, every PR | a fast `scripts` job first (shellcheck over every script + `./scripts/vox test`), then `./gradlew test` for all modules, `assembleDebug` for all six apps, `compileDebugAndroidTestSources`, and `vox check pairing` over the six APKs |
| `validate-schemas.yml` | pushes touching `remote-schemas/**` | validates the shipped schema files |
| `verify-schemas.yml` | pushes touching `remote-schemas/**` or `scripts/sign_schemas.sh` | verifies the schema manifest signature and every schema hash |
| `verify-vendor-patches.yml` | pushes touching a vendored fork or its patches | asserts each fork is exactly upstream + `patches/` |
| CodeQL | GitHub's own setup | `actions`, `c-cpp`, `python` analyses |

CI runs with `-PvoxSkipNativePrep` and asks for 6 GB of heap (`gradle.properties` requests 4 GB,
which is sized for building one app on a laptop; dexing all six at once ran D8 out of memory on
`vox-vision`, the heaviest). It restores the OpenCV build from a cache keyed on the pinned
`vendor/opencv` commit, `scripts/build_opencv_android.sh` and the NDK version; on a hit, the
submodule fetch, the Android SDK setup and the NDK install are all skipped. OpenCV cannot be skipped
the way whisper can — `vendor/ppocr-sdk/opencv/java` is generated by that build and `org.opencv.*`
comes from it.

A release workflow does **not** run on an ordinary commit. Each is filtered to one path.

## GitHub Actions release workflows

Each app has its own `.github/workflows/release-<app>.yml` (e.g. `release-expenses.yml`). They're
independent — releasing one app never touches another's release history. Common shape:

```yaml
on:
  push:
    tags: ["<app>-v*"]
    branches: [main]
    paths: ["vox-<app>/build.gradle.kts"]
  workflow_dispatch:
    inputs:
      publish:
        type: boolean
        default: false
      dlc_mode:          # commander/vision only; blank = whatever gradle.properties says
        type: choice
        options: ['', 'minimal', 'full']
```

Commander and Vision resolve `voxDlc` in a "Resolve DLC mode" step that reads `gradle.properties`,
and use that answer for the build **and** for whether to attach the `.so` release assets. The mode is
never declared in the workflow — see [How much ships inside the APK](#how-much-ships-inside-the-apk-voxdlc).

Gradle signs the APK and the bundle directly: the workflows set `RELEASE_KEYSTORE_PATH` and
`RELEASE_KEYSTORE_PASSWORD` on the build step, and Commander's asserts the result with
`apksigner verify` afterwards. Gradle only signs when it sees those variables, which is exactly the
kind of thing worth asserting rather than assuming — an unsigned or differently-signed APK installs
over nothing, and for signature-level IPC it breaks first-party routing silently. The schemes are
stated in `signingConfigs` (`v1=false, v2=true, v3=true`) rather than left to AGP's default of v2
alone, because every published release so far is v3 and an installed app updates only from an APK
carrying the same certificate.

- **Push to `main` that touches `vox-<app>/build.gradle.kts`** — the usual path. A `check_bump` step
  asks GitHub whether a Release already exists for the computed tag; if it does, every downstream
  step is skipped via `if: steps.check_bump.outputs.changed != 'false'`. This is what makes "bump
  versionCode, commit, push" the normal release trigger.

  It asks GitHub rather than diffing `HEAD~1`, because diffing broke whenever a push landed more
  than one commit at once: `HEAD~1` then lands *inside* the same push already showing the bumped
  value, so `prev == curr` and the release was silently skipped.
- **Direct tag push** (`<app>-v1.2`) — builds and releases under that exact tag.
- **`workflow_dispatch`** — builds. It publishes only when you tick `publish`:

  ```
  gh workflow run release-<app>.yml -f publish=true
  ```

  or **Actions → "Build Vox \<App> Release APK" → Run workflow**, which shows the checkbox. Default
  is off, because six accidental dispatches once published six GitHub Releases. Ticking it is the
  way to publish a build whose version was already released, or to re-publish one whose build
  succeeded and whose publish step failed.

  `check_bump` doesn't run on a dispatch at all (its own `if` is `github.event_name == 'push'`), so
  a dispatched publish does not second-guess itself.

Tag convention: `<app>-v<versionName>` (e.g. `expenses-v0.15`), resolved by the shared
`.github/actions/compute-release-tag` action. A `versionName` containing `-beta`/`-rc`/`-alpha` is
marked as a GitHub prerelease automatically.

Each release job is serialised by `concurrency: release-<app>`, queued rather than cancelled — two
pushes landing close together would otherwise both force-move the same tag and both delete the same
release before recreating it.

### The tag step, and the one way it fails

Publishing force-moves the app's tag onto the built commit:

```bash
git tag -f "$TAG"
git push origin "$TAG" --force
```

`GITHUB_TOKEN` can never hold the `workflows` scope, and what that scope gates is making a ref point
at a tree whose `.github/workflows` content differs from the repository's own. So this step fails
when a workflow edit lands on `main` while a release is building — the tag then moves to a commit
whose workflow files are already stale:

```
! [remote rejected] commander-v0.16-beta -> commander-v0.16-beta
  (refusing to allow a GitHub App to create or update workflow `.github/workflows/release-cale…`)
```

**Don't edit workflow files while a release is building.** Routing the same operation through the
`git/refs` REST API was tried and is subject to the identical rule — it only trades an error that
names the offending file for a bare `Resource not accessible by integration (HTTP 403)`. A PAT with
`workflow` scope in a secret is the only thing that would make the step immune; nothing else about
the setup needs changing.

## Fastlane screenshots

Each app's F-Droid/IzzyOnDroid metadata screenshots live at
`<app>/fastlane/metadata/android/en-US/images/phoneScreenshots/` as numbered `.png`/`.jpeg` files,
some carrying a descriptive suffix (`1.jpeg`, `21_rules_manager_mode_selector.png`). Captured
manually against the emulator (or, for UI that only exists after real usage — e.g. the expense
duplicate-review screen — copied in from a physical-device screenshot):

```
adb exec-out screencap -p > shot.png
```

For navigating to a specific screen precisely (rather than guessing tap coordinates from a
screenshot), dump the current layout and read exact element bounds instead of eyeballing
coordinates:

```
adb shell uiautomator dump
adb pull /sdcard/window_dump.xml
# grep the target element's `bounds="[x1,y1][x2,y2]"` and tap its center
```

## F-Droid distribution

VoxApps APKs are also mirrored into a separate, serverless F-Droid repo:
[`razvan-eduard/vox-fdroid-repo`](https://github.com/razvan-eduard/vox-fdroid-repo) (see that
repo's own `USAGE.md`).

Two halves, in this repo and that one:

- **Metadata** is pushed from here. `deploy-fdroid.yml` triggers on `workflow_run` completion of any
  of the six release workflows, gated on `conclusion == 'success'`, runs
  `scripts/sync_fdroid_metadata.sh`, and commits the regenerated `metadata/` tree into
  `vox-fdroid-repo`. It regenerates metadata for *all* apps every run and is serialised
  (`concurrency: deploy-fdroid-metadata`, not cancelled) so two releases finishing minutes apart
  don't race on the same push.
- **APKs** are pulled by that repo, from this repo's GitHub Releases, on its own daily cron or via
  `gh workflow run deploy.yml --repo razvan-eduard/vox-fdroid-repo`.

Changelogs are filtered to `feat|fix|perf|refactor` commits (`KEEP_TYPES` in
`sync_fdroid_metadata.sh`) and the *What's New* field is capped at 500 characters.

Note the gate is on the release workflow *succeeding*, not on it publishing: a dispatched build with
`publish=false` still counts as success, so `deploy-fdroid` runs and regenerates metadata against
whatever releases already exist. Harmless, just not useful.

## Dependencies and bots

`.github/dependabot.yml` covers two ecosystems, `gradle` and `github-actions`, weekly, limit 5 open
PRs each. Minor and patch updates are **grouped** into one PR per ecosystem (`gradle-routine`,
`actions-routine`); majors arrive one apiece, deliberately — those are the ones that change
behaviour. **Nothing auto-merges.** CI runs on Dependabot's PRs like any other, and a person merges.

Seven `sync-*.yml` workflows watch vendored native upstreams, **one per day** at 06:00 UTC — vosk
(Mon), newpipe-extractor (Tue), openwakeword (Wed), opencv (Thu), ppocr-sdk (Fri) — plus two
monthly: whisper (2nd, 09:00 UTC) and llama (3rd, 09:00 UTC, the day after). They apply the
update, try to build it, and open a PR saying whether it compiled. **None of
them merges itself**, and a PR you close stays closed. See `BUILD_TIME_DEPENDENCIES.md` for what each
vendored fork patches, how those patches are kept, and why PaddleOCR is deliberately followed a week
behind its tip.
