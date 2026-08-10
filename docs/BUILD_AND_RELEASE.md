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

This used to be untrue, and the difference mattered: the DLC libraries were stripped out of the
built zip by a shell script that then re-signed the APK, so a local `assembleRelease` produced a
fully-bundled APK that **could not exercise the DLC download path at all** — two bugs in that path
reached users because of it. AGP now does the packaging, in both modes, for the APK and the bundle
alike. See [BUILD_TIME_DEPENDENCIES.md](BUILD_TIME_DEPENDENCIES.md#why-the-dlc-libs-were-stripped-by-hand)
for why it was ever done the other way.

### How much ships inside the APK (`voxDlc`)

One property decides whether the native payload is bundled or downloaded, and it is **`minimal` by
default** (`gradle.properties`):

```
./gradlew :vox-commander:assembleRelease                    # minimal — the default
./gradlew :vox-commander:assembleRelease -PvoxDlc=full      # as IzzyOnDroid's 30MB cap required
```

| | `minimal` (default) | `full` |
|---|---|---|
| Commander APK | ~47 MB, 11 libs inside | ~24 MB, 5 libs inside |
| Vision APK | ~61 MB, 15 libs inside | ~16 MB, 5 libs inside |
| First launch | nothing downloads; works offline | 53 MB (Commander) / 43 MB (Vision) fetched on the splash |
| Whisper | on demand, unchanged | on demand, unchanged |

**Whisper is unaffected by the switch.** It is the one genuinely optional payload — ~193 MB fetched
only if you choose Whisper STT, and the Vulkan variant only where the GPU supports it — so it is
excluded by AGP in both modes. Everything else was a *mandatory* second download: `ESSENTIAL_LIBS`
is not a figure of speech, and splitting those out deferred nothing while adding a splash-screen
download that can fail offline.

The property reaches the app through `BuildConfig.DLC_MODE`, which is what `NativeLibs` reads to
decide whether to fetch anything. **That is deliberate and load-bearing**: the packaging decision and
the download decision have to be the same decision. Build one way and package the other and you ship
an APK missing libraries nothing will ever fetch.

**`gradle.properties` is the only place the mode is written.** The release workflows read it in a
"Resolve DLC mode" step and use that answer for the build and for whether to attach the `.so`
assets. They used to declare `VOX_DLC: minimal` themselves, which made sense while Gradle and a
shell packaging script each needed the value and disagreeing shipped a broken APK — but the script
is gone, and needing to know the mode is not a reason to state it a second and third time. A
dispatched run can override it for that run only, via the `dlc_mode` input; blank means "whatever
`gradle.properties` says".

An invalid value fails the build rather than silently choosing:

```
voxDlc must be 'minimal' or 'full', got 'nonsense'
```

**Both apps now exclude the same way** — AGP's `packaging.jniLibs.excludes`, applied per variant.
Commander used to strip post-build instead, on the belief that AGP could not exclude its libs; that
was a misdiagnosis, and the difference between the two apps is gone. What remains is a real one:
Vision's DLC libs are files in `vox-vision/src/main/jniLibs/`, so they exist on disk for upload no
matter how the APK is packaged, while Commander's come from AAR dependencies and are excluded before
any build output contains them. `:vox-commander:collectDlcLibs` therefore stages them straight from
the resolved dependencies; it runs automatically after `assembleRelease` in `full`, and writes to
`vox-commander/build/dlc-libs/`.

Two artifacts provide a `libonnxruntime.so`: sherpa-onnx's own (~21 MB) and onnxruntime-android's
(~28 MB). They are different binaries, and only sherpa's exports the symbol version
`libsherpa-onnx-jni.so` needs. `collectDlcLibs` picks by artifact rather than by file name, and
fails the build if the choice is ever ambiguous.

### Skipping the native prep

`vox-commander`'s `preBuild` compiles whisper.cpp from its submodule — that's the only script left in
a build, and it produces output the app links. The three upstream-version checks used to run here too
and no longer do: they belong to the weekly sync bots, and one of them wrote to the vendored source
tree while a compile was running.

```
./gradlew :vox-commander:assembleDebug -PvoxSkipNativePrep
```

skips the whisper compile for a build that only needs to know whether the Kotlin compiles.
Everything downstream of `preBuild` still runs. `copyShippedSchemas` is **not** skippable — the
schema tests read the assets it generates.

## What runs on every push

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci.yml` | every push to `main`, every PR | `./gradlew test` for all modules, then `assembleDebug` for all six apps |
| `validate-schemas.yml` | pushes touching `remote-schemas/**` | validates the shipped schema files |
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
```

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
`<app>/fastlane/metadata/android/en-US/images/phoneScreenshots/{1,2,3,...}.png`. Captured
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

Six `sync-*.yml` workflows watch vendored native upstreams, **one per day** at 06:00 UTC — vosk
(Mon), newpipe-extractor (Tue), openwakeword (Wed), opencv (Thu), ppocr-sdk (Fri) — plus whisper
monthly. They apply the update, try to build it, and open a PR saying whether it compiled. **None of
them merges itself**, and a PR you close stays closed. See `BUILD_TIME_DEPENDENCIES.md` for what each
vendored fork patches, how those patches are kept, and why PaddleOCR is deliberately followed a week
behind its tip.
