# Build & Release Guide

How to build APKs locally, how the per-app GitHub Actions release workflows work, how to force a
rebuild without bumping a version, and how fastlane screenshots are captured. For vendored
native-library build steps (Whisper.cpp, Vosk, OpenCV, etc.) see `BUILD_TIME_DEPENDENCIES.md`
instead — this doc is about the app APKs themselves.

## Local builds

**Debug** (emulator-only — see the release-only install rule below):

```
./gradlew :vox-<app>:assembleDebug
```

**Signed release** (installable on a real device without wiping its signed data):

```
cd /Users/swimnobody/StudioProjects/VoxApps
export RELEASE_KEYSTORE_PATH=~/Downloads/voxapps-release.jks
export RELEASE_KEYSTORE_PASSWORD=$(cat ~/Downloads/keystore_password.txt | tr -d '[:space:]')
./gradlew :vox-<app>:assembleRelease --no-daemon
```

`<app>` is one of `vox-commander`, `vox-expenses`, `vox-calendar`, `vox-notes`, `vox-hub`,
`vox-vision`. Multiple `assembleRelease` targets can be listed in one invocation. Without the two
env vars set, `assembleRelease` still succeeds but produces an **unsigned** APK that can't be
installed over an existing signed install.

**Never install a debug APK over a real device's existing release install** — the signature
mismatch forces Android to uninstall first, wiping that app's local data. Debug APKs are fine on a
disposable emulator only.

## GitHub Actions release workflows

Each app has its own `.github/workflows/release-<app>.yml` (e.g. `release-expenses.yml`). They're
independent — releasing one app never touches another's release history. Common shape:

```yaml
on:
  push:
    tags: ["<app>-v*"]
    branches: [main]
    paths: ["vox-<app>/build.gradle.kts"]
  workflow_dispatch: {}
```

- **Push to `main` that touches `vox-<app>/build.gradle.kts`** — the usual path. A `check_bump`
  step diffs the previous commit's `versionCode` against the current one; if unchanged, every
  downstream step (tests, build, release) is skipped via `if: steps.check_bump.outputs.changed !=
  'false'`. This is what makes "bump versionCode, commit, push" the normal release trigger.
- **Direct tag push** (`<app>-v1.2`) — builds and releases under that exact tag.
- **`workflow_dispatch`** (manual run) — **bypasses `check_bump` entirely**, because that step's
  own `if` condition is `github.event_name == 'push'`. A manual dispatch run always builds and
  releases, version bump or not. This is the way to force a rebuild (picking up a code fix,
  dependency bump, etc.) without touching `versionCode`/`versionName`.

  ```
  gh workflow run release-<app>.yml   # e.g. release-expenses.yml
  ```

  or via the GitHub UI: **Actions → "Build Vox \<App> Release APK" → Run workflow** (branch
  `main`).

  Caveat: the release tag is still computed from the *current* `versionName` (see
  `.github/actions/compute-release-tag/action.yml`), so a forced run with no version change
  recreates the same tag; `softprops/action-gh-release` updates that release's assets in place
  rather than failing.

Tag convention: `<app>-v<versionName>` (e.g. `expenses-v0.15`). A `versionName` containing
`-beta`/`-rc`/`-alpha` is marked as a GitHub prerelease automatically.

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
repo's own `USAGE.md`). It pulls the latest tagged release per app from this repo's GitHub
Releases — so once a `release-<app>.yml` run above publishes a release, either the daily cron or a
manual `gh workflow run deploy.yml --repo razvan-eduard/vox-fdroid-repo` picks it up.
