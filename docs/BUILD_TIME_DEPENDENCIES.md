# Build-Time Native & Vendored Dependencies

> Monorepo-wide reference (not scoped to a single app — covers `vox-commander` and `vox-vision`
> dependencies alike). For `vox-commander`-specific feature detail on Wake Word engines and STT, see
> [`docs/TECHNICAL_DOCUMENTATION.md`](TECHNICAL_DOCUMENTATION.md) §2–3; this document is about the
> *build-time mechanism* — what gets fetched, built, or patched before compilation, and how it stays
> in sync with upstream — as its own cross-cutting topic.

VoxApps depends on five native/ML libraries that aren't simple Maven artifacts. Each falls into one of
two patterns:

| Pattern | Meaning | Used by |
|---|---|---|
| **A — binary dependency, version-check only** | A normal Maven/JitPack artifact; no source vendored, nothing compiled locally. A script just checks whether a newer published version exists. | Vosk |
| **B — vendored source, built and/or patched locally** | The actual source (unmodified, or with a small local patch) lives in this repo/is compiled from a submodule at build time, because the upstream binary is broken, unmaintained, or missing a feature we need. | Whisper.cpp, OpenWakeWord, OpenCV, PaddleOCR ppocr-sdk |

## At a glance

| Dependency | Module | Pristine reference | Local copy | Patched? | Built at build time? | Gradle task | Sync workflow |
|---|---|---|---|---|---|---|---|
| Vosk | `vox-commander` | — (JitPack coordinate) | — | No | No | `autoCheckVosk` | `sync-vosk.yml` (weekly) |
| Whisper.cpp | `vox-commander/src/main/cpp/whisper.cpp` | *is* the submodule | *is* the submodule | No | Yes (CMake, every build if stale) | `autoCompileWhisper` | `sync-whisper.yml` (monthly, compile-check only) |
| OpenWakeWord | `core/wakeword` | `vendor/openwakeword-android-kt` (submodule) | `core/wakeword/src/...` | Yes — RMS silence gate | No (plain Kotlin/ONNX Runtime) | `autoCheckOpenWakeWord` | `sync-openwakeword.yml` (weekly) |
| OpenCV | `vendor/ppocr-sdk/opencv/` (gitignored output) | `vendor/opencv` (submodule) | — (build output only, not vendored as source) | No | Yes (CMake, skips only if the built commit matches the pinned submodule commit — see below) | `autoCompileOpenCv` | `sync-opencv.yml` (weekly) |
| PaddleOCR ppocr-sdk | `vendor/ppocr-sdk` | `vendor/paddleocr-upstream` (submodule, sparse-checked-out) | `vendor/ppocr-sdk/src/...` | Yes — load models from bytes/files | No (plain Kotlin) | none yet | `sync-ppocr-sdk.yml` (weekly) |

`autoCompileOpenCv`'s Gradle task is wired into `preBuild`, but at `vendor/ppocr-sdk`'s own module
level (it runs automatically whenever that module builds) rather than a root-level task like
`autoCompileWhisper`/`autoCheckVosk`/`autoCheckOpenWakeWord` are.

---

## Pattern A: Vosk (version-check only)

Vosk (`com.alphacephei:vosk-android`, resolved via JitPack) is consumed as a plain binary dependency —
no source vendored, nothing compiled. Dependabot's Gradle updater doesn't reliably track this
JitPack-style coordinate (confirmed empirically — it has never opened a PR for it), so:

- **`scripts/check_vosk_version.sh`** — queries the JitPack API (with a Maven Central search fallback)
  for the latest published version, compares it against `gradle/libs.versions.toml`, and prints an
  update notice (no automatic change).
- **`autoCheckVosk`** Gradle task (`vox-commander/build.gradle.kts`) runs this on every `preBuild`.
- **`.github/workflows/sync-vosk.yml`** — same check on a weekly schedule; bumps
  `libs.versions.toml`, runs `assembleDebug` + `testDebugUnitTest`, and — the one `sync-*.yml` that
  does — **auto-merges on green**, same as `dependabot-automerge.yml`. Vosk carries no local patch and
  isn't compiled in-repo, the closest risk profile to a normal Dependabot bump; accepted residual risk
  is that a green build can't confirm wake-word model-loading/recognition accuracy, only that Vosk's
  API surface still resolves and compiles.

---

## Pattern B: Whisper.cpp (vendored unmodified, compiled at build time)

`vox-commander/src/main/cpp/whisper.cpp` is a **git submodule** pointing at `ggerganov/whisper.cpp`
directly — used unmodified (no local patch), compiled from source via CMake because it's a native
library with hardware-specific build flags (Vulkan GPU acceleration, NEON, etc.) that no prebuilt
artifact could cover well.

- **`scripts/check_whisper.sh`** — the most involved of these scripts, because it does real work, not
  just a check:
  1. Initializes the submodule if missing.
  2. Checks for a newer stable upstream tag (interactive prompt in a terminal; just a log line in CI/
     Android Studio's non-interactive Gradle invocation).
  3. Snapshots the current `.so` libraries as a rollback point.
  4. Configures + builds via CMake/Ninja (hybrid CPU/Vulkan), targeting the NDK found under
     `~/Library/Android/sdk/ndk`.
  5. Verifies the resulting `libwhisper.so` actually exports `whisper_init` before deploying it to
     `jniLibs/arm64-v8a/` — if anything in steps 4–5 fails, it **automatically rolls back** to the
     previous git revision and restores the previous `.so` backup, so a bad build never leaves the
     project in a broken state.
  6. If an upgrade/force-rebuild actually happened, publishes the new `.so` files as a GitHub Release
     (DLC) via `scripts/publish_whisper_libs.sh` — release builds exclude Whisper's native libs from
     the APK (~166MB → ~19MB) and download them on demand.
- **`autoCompileWhisper`** Gradle task runs this on every `preBuild`.
- **`.github/workflows/sync-whisper.yml`** — monthly scheduled: bumps the submodule pin to a newer
  stable tag and confirms it still compiles, opening a PR — but deliberately **never** calls
  `publish_whisper_libs.sh` itself. Publishing the production DLC is a manual, human-reviewed step
  after the PR is merged, because CI can only verify "it compiles," never "it still transcribes
  correctly" — that needs an actual on-device sanity check.

**Known gap (planned, not yet done):** `check_whisper.sh` assumes Vulkan headers
(`vulkan-headers`/`spirv-headers`/`shaderc`) are already installed via Homebrew
(`VULKAN_HEADERS_BASE`/`SPIRV_HEADERS_BASE`/`SHADERC_BASE`, falling back to `/usr/local` if `brew` is
missing entirely) — it doesn't yet check for or install Homebrew/these formulae itself if they're
absent, so a fresh machine needs them installed manually first. Planned follow-up: detect Homebrew is
missing and install it, then `brew install` the three formulae, before invoking CMake.

---

## Pattern B: OpenWakeWord (vendored + patched)

Full detail already lives in
[`docs/TECHNICAL_DOCUMENTATION.md` §2 "OpenWakeWord Fork & Sync"](TECHNICAL_DOCUMENTATION.md#openwakeword-fork--sync) —
summarized here for completeness alongside its siblings:

| Path | Role |
|---|---|
| `vendor/openwakeword-android-kt` | Git submodule — pristine upstream source at a pinned tag. Reference only, never compiled directly. |
| `core/wakeword/` | Local Gradle module — vendored + patched copy of upstream's `:wakeword` module, compiled into `vox-commander`. |
| `core/wakeword/src/main/kotlin/.../audio/AudioRecorder.kt` | The one patched file — an RMS silence gate. |
| `core/wakeword/patches/0001-rms-silence-gate.patch` | The patch as a real unified diff — regenerate with `scripts/regen_openwakeword_patch.sh`. |
| `core/wakeword/NOTICE` / `LICENSE` | Apache-2.0 attribution chain. |

- **`scripts/check_openwakeword_version.sh`** — non-destructive dry-run: is a newer upstream tag
  available, and would the stored patch still `git apply --check` cleanly against it?
- **`autoCheckOpenWakeWord`** Gradle task runs this on every `preBuild`.
- **`.github/workflows/sync-openwakeword.yml`** — weekly: bumps the submodule, fully re-vendors
  `core/wakeword`'s sources, tries to `git apply` the stored patch, and if it applies cleanly *and* the
  module compiles + unit tests pass, opens a PR that's already ready to merge. Only surfaces a
  manual-merge PR if the patch genuinely conflicts.

---

## Pattern B: OpenCV (vendored unmodified, compiled at build time)

`vendor/opencv` is a **git submodule** pointing at `opencv/opencv` directly, pinned to tag `4.13.0`,
used unmodified. It replaces a stale, unmaintained Maven dependency
(`com.quickbirdstudios:opencv:4.5.3`, last published 2021-09-15) whose prebuilt native library fails
to `dlopen` on modern Android (missing Bionic libc symbol `__sfp_handle_exceptions`).

- **`scripts/build_opencv_android.sh`** — invokes CMake directly (not OpenCV's own
  `platforms/android/build_sdk.py` wrapper, which has build-list restrictions that conflict with the
  combined-module path `--shared` builds take). Mirrors the approach
  [MakeACopy](https://github.com/egdels/makeacopy) uses for the same PaddleOCR-on-Android problem:
  - Only builds `core` + `imgproc` + `imgcodecs` (everything else — video, ML, DNN, highgui,
    stitching, etc. — is `OFF`; not needed for OCR).
  - Two-step build: `gen_opencv_java_source` first (JNI codegen), then the rest.
  - Tolerates OpenCV's own bundled internal Gradle sub-project failing (a Kotlin/JDK toolchain
    mismatch unrelated to what's actually needed) — the real native library and Java bindings are
    produced by plain Ninja targets *before* that sub-project even runs, and are verified explicitly
    afterward regardless of that sub-project's outcome.
  - Copies `libopencv_java4.so` + the per-module shared libs it dynamically links against
    (`libopencv_core.so`, `libopencv_imgproc.so`, `libopencv_imgcodecs.so` — required in `jniLibs`
    since `BUILD_SHARED_LIBS=ON` doesn't statically link them in) into
    `vendor/ppocr-sdk/opencv/libs/arm64-v8a/`, stripped via `llvm-strip`.
  - Copies the generated Java bindings source into `vendor/ppocr-sdk/opencv/java/` — including a
    second copy step for `org.opencv.android.{Utils,OpenCVLoader,StaticHelper,FpsMeter}` (generated
    into a separate `gen/android` tree, not `gen/java`), deliberately excluding
    `JavaCameraView`/`CameraBridgeViewBase`/`CameraActivity` (need an app module's `R`/`BuildConfig` for
    camera-preview UI Vision doesn't use — Vision captures via CameraX instead).
  - Idempotent **and version-aware**: stamps the built `vendor/opencv` commit SHA into
    `vendor/ppocr-sdk/opencv/.built-commit` on success, and only skips a rebuild if that marker matches
    the submodule's *current* pinned commit — so bumping the submodule and rerunning this script
    reliably triggers a real rebuild, rather than silently reusing stale output just because the
    output files happen to already exist (an actual bug this session found and fixed — the original
    check only asked "does output exist," never "is it for the right version").
- **`autoCompileOpenCv`** Gradle task (`vendor/ppocr-sdk/build.gradle.kts`) runs this on `preBuild`.
- **`vendor/ppocr-sdk/opencv/`** is the build's output directory — **gitignored** (it's regenerated,
  not source this repo maintains).
- **`.github/workflows/sync-opencv.yml`** — weekly: bumps the submodule to the latest upstream tag and
  confirms it still builds (compiling `vendor/ppocr-sdk` for real runs `autoCompileOpenCv`, since
  ppocr-sdk's Kotlin source directly imports the generated `org.opencv.*` bindings), opening a PR —
  ready to review if it compiles, flagged if it doesn't. No patch involved (OpenCV is vendored
  unmodified), so there's no "conflict" outcome here, unlike OpenWakeWord/ppocr-sdk's sync workflows —
  just compiles or doesn't.

---

## Pattern B: PaddleOCR ppocr-sdk (vendored + patched)

`vendor/ppocr-sdk` vendors PaddleOCR's Android SDK (`deploy/ppocr-android/ppocr-sdk`) — the same
"pristine-submodule + vendored-patched-module" split as OpenWakeWord:

| Path | Role |
|---|---|
| `vendor/paddleocr-upstream` | Git submodule — pristine upstream source, pinned to commit `211989f046cc1878460f9e65574690c00a127a1a`. **Sparse-checked-out** to just `deploy/ppocr-android/ppocr-sdk` — PaddleOCR itself is a ~2GB monorepo we otherwise have no use for; a blobless partial clone (`--filter=blob:none --sparse`) plus a shallow fetch of just the pinned commit keeps this to ~22MB instead. Reference only, never compiled directly. |
| `vendor/ppocr-sdk/src/...` | Local Gradle module — vendored + patched copy, compiled into `vox-vision`. |
| `vendor/ppocr-sdk/src/main/java/com/paddle/ocr/{engine/ORTSessionManager,model/ModelConfig,engine/OCREngine,PaddleOCR}.kt` | The 4 patched files — see below. |
| `vendor/ppocr-sdk/patches/0001-load-models-from-bytes.patch` | The patch as a real unified diff — regenerate with `scripts/regen_ppocr_sdk_patch.sh`. |
| `vendor/ppocr-sdk/NOTICE` | Apache-2.0 attribution + provenance. |
| `vendor/ppocr-sdk/opencv/` | *Not* part of this vendoring — OpenCV's build output, see the section above. Gitignored separately. |

**The patch:** upstream's `ORTSessionManager`/`ModelConfig`/`OCREngine`/`PaddleOCR` only ever read
model/config files from `context.assets`. Vision downloads its OCR models at runtime into
app-internal storage (never bundled in the APK), so the patch adds purely-additive overloads
(`loadModels(ByteArray, ByteArray)`, `ModelConfig.parse(String, sourceLabel)`, `OCREngine`'s
`detModelBytes`/`recModelBytes`/`recConfigContent` params, `PaddleOCR.create(..., detModelFile: File,
recModelFile: File, recConfigFile: File)`) — every original asset-path-based method/constructor is
untouched.

- **`scripts/regen_ppocr_sdk_patch.sh`** — regenerates the patch file from the current state of
  `vendor/ppocr-sdk/` vs. the pinned `vendor/paddleocr-upstream` submodule, across all 4 touched files;
  sanity-checks by re-applying the freshly generated patch to a copy of the pristine files and
  confirming it reproduces the current patched source byte-for-byte.
- **`scripts/check_ppocr_sdk_version.sh`** — non-destructive dry-run: has upstream's default branch
  moved past the pinned commit, and would the stored patch still apply cleanly against the newer tree?
- **`.github/workflows/sync-ppocr-sdk.yml`** — weekly: re-clones `vendor/paddleocr-upstream` sparse +
  shallow at upstream's current default-branch tip, fully re-vendors
  `vendor/ppocr-sdk/src/main/java/com/paddle` from it, tries to `git apply` the stored patch, and if it
  applies cleanly *and* the module compiles, opens a PR that's already ready to merge. Only surfaces a
  manual-merge PR if the patch genuinely conflicts. Unlike OpenWakeWord's default-branch tags,
  PaddleOCR has no relevant release tags for this path, so upstream is tracked by comparing raw commit
  SHAs on its default branch. The compile-check step also builds OpenCV from source first (via
  `autoCompileOpenCv`) since `vendor/ppocr-sdk`'s Kotlin source directly imports its generated
  `org.opencv.*` bindings — a heavier step than OpenWakeWord's self-contained module needs.

---

## Common conventions

Across Pattern B dependencies:

- **Submodule = pristine reference only.** It's never built from directly — it exists purely so a
  local patch can be a real, `git apply`-able diff instead of an "informational" text file with
  nothing to verify it against.
- **The actual vendored module's source is committed to this repo**, already patched. This is
  intentional, not something to "clean up" — it's the buildable artifact. Only the *submodule*
  appears in `git status` as a single gitlink line; the vendored module's own files show up like any
  other tracked source (because they are).
- **Naming**: `scripts/check_<name>_version.sh` (dry-run, non-destructive, safe to run anytime),
  `scripts/regen_<name>_patch.sh` (regenerates the stored patch — only run when you've intentionally
  changed the patch itself), `.github/workflows/sync-<name>.yml` (the scheduled job that does the real
  work: re-vendor + re-apply + build/test + open a PR). Only `sync-vosk.yml` auto-merges on green —
  every other one always waits for manual review, since a green build can't verify audio/wake-word/
  speech/vision *behavior*, only that the code still compiles.
- **NOTICE** file per vendored module: upstream attribution, the exact pinned commit/tag, what the
  local patch does and why, and pointers to the check/regen scripts.

## Open items

- **`check_whisper.sh`**'s Homebrew dependency detection (Vulkan headers) doesn't yet install Homebrew
  or the required formulae if missing — see the Whisper.cpp section above. Deliberately deferred.

All five dependencies now have a scheduled sync workflow, and the one identified staleness-detection
bug (OpenCV's build script skipping a rebuild purely because output existed, regardless of whether it
matched the pinned commit) has been fixed — see the OpenCV section above.
