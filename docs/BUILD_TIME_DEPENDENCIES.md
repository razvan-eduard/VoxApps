# Build-Time Native & Vendored Dependencies

> Monorepo-wide reference (not scoped to a single app — covers `vox-commander` and `vox-vision`
> dependencies alike). For `vox-commander`-specific feature detail on Wake Word engines and STT, see
> [`docs/TECHNICAL_DOCUMENTATION.md`](TECHNICAL_DOCUMENTATION.md) §2–3; this document is about the
> *build-time mechanism* — what gets fetched, built, or patched before compilation, and how it stays
> in sync with upstream — as its own cross-cutting topic.

VoxApps depends on nine native/ML libraries that aren't simple Maven artifacts. Each falls into one of
two patterns:

| Pattern | Meaning | Used by |
|---|---|---|
| **A — binary dependency, version-check only** | A normal Maven/JitPack artifact; no source vendored, nothing compiled locally. A script just checks whether a newer published version exists. | Vosk, NewPipeExtractor, onnxruntime-android |
| **B — vendored source, built and/or patched locally** | The actual source (unmodified, with a small local patch, or ported) lives in this repo/is compiled from a submodule at build time, because the upstream binary is broken, unmaintained, or missing a feature we need. | Whisper.cpp, llama.cpp, OpenWakeWord, OpenCV, PaddleOCR ppocr-sdk, DocQuad SDK |

## At a glance

| Dependency | Module | Pristine reference | Local copy | Patched? | Built at build time? | Gradle task | Sync workflow |
|---|---|---|---|---|---|---|---|
| Vosk | `vox-commander` | — (JitPack coordinate) | — | No | No | `autoCheckVosk` | `sync-vosk.yml` (Mon) |
| NewPipeExtractor | `vox-commander` | — (JitPack coordinate) | — | No | No | `autoCheckNewPipeExtractor` | `sync-newpipe-extractor.yml` (Tue) |
| onnxruntime-android | `vox-vision` (via `vendor/ppocr-sdk`), `core/wakeword` | — (Maven Central coordinate) | — | No | No | none | Dependabot (weekly) |
| Whisper.cpp | `vox-commander/src/main/cpp/whisper.cpp` | *is* the submodule | *is* the submodule | No | Yes (CMake, every build if stale) | `autoCompileWhisper` | `sync-whisper.yml` (monthly) |
| llama.cpp | `vox-commander/src/main/cpp/llama.cpp` | *is* the submodule | *is* the submodule | No | Yes (CMake, every build if stale) | `autoCompileLlama` | `sync-llama.yml` (monthly) |
| OpenWakeWord | `core/wakeword` | `vendor/openwakeword-android-kt` (submodule) | `core/wakeword/src/...` | Yes — 3 patches | No (plain Kotlin/ONNX Runtime) | `autoCheckOpenWakeWord` | `sync-openwakeword.yml` (Wed) |
| OpenCV | `vendor/ppocr-sdk/opencv/` (gitignored output) | `vendor/opencv` (submodule, tag `5.0.0`) | — (build output only, not vendored as source) | No | Yes (CMake, skips only if the built commit matches the pinned submodule commit — see below) | `autoCompileOpenCv` | `sync-opencv.yml` (Thu) |
| PaddleOCR ppocr-sdk | `vendor/ppocr-sdk` | `vendor/paddleocr-upstream` (submodule, sparse-checked-out) | `vendor/ppocr-sdk/src/...` | Yes — 4 patches | No (plain Kotlin) | none yet | `sync-ppocr-sdk.yml` (Fri) |
| MakeACopy DocQuad SDK | `vendor/docquad-sdk` (used by `vox-vision`) | `vendor/makeacopy-upstream` (submodule, sparse-checked-out) | `vendor/docquad-sdk/src/...` | No — a from-scratch Kotlin port of 4 upstream Java files (see its `NOTICE`) | No (plain Kotlin) | none | none — `scripts/check_docquad_sdk_version.sh` via `./scripts/vox check docquad`, informational only: a textual patch cannot be dry-run across a language rewrite, so it reports whether the ported files moved upstream and a human decides on a re-port |

`autoCompileOpenCv`'s Gradle task is wired into `preBuild`, but at `vendor/ppocr-sdk`'s own module
level (it runs automatically whenever that module builds) rather than a root-level task like
`autoCompileWhisper`/`autoCheckVosk`/`autoCheckOpenWakeWord` are.

### What actually runs at build time

Only three of these scripts are wired into a build:

| Task | Script | Module | Effect on your tree |
|---|---|---|---|
| `autoCompileWhisper` | `check_whisper.sh` | `vox-commander` | Builds the `.so` files. Can check out a newer whisper tag, but only with `--upgrade` or a typed `y` at an interactive prompt — unreachable under Gradle, whose `Exec` stdin is not a TTY. |
| `autoCompileLlama` | `check_llama.sh` | `vox-commander` | Builds `libllama.so` (CPU backend, static ggml, stripped at deploy). Same upgrade discipline as whisper's. |
| `autoCompileOpenCv` | `build_opencv_android.sh` | `vendor/ppocr-sdk` | Builds from the **pinned** submodule; never moves the pin. Early-exits when the built commit matches. |

The three `autoCheck*` tasks are **deliberately not wired into `preBuild`**. "A newer Vosk exists" is
a maintenance fact, and its home is the weekly sync workflow that opens a PR. Wiring it into a build
would cost three network round-trips per build, make builds behave differently offline, and — the
reason it must not be — the patch dry-run *overwrites vendored source files*, which nothing attached
to a compile may do. They are runnable on demand:

```
./scripts/vox check              # every upstream, one table
./scripts/vox check vosk         # just one
./gradlew :vox-commander:checkUpstream
```

Both dry-run scripts restore the swapped files on `EXIT`/`INT`/`TERM`, so an interrupted check
cannot leave pristine upstream files in place of the patched ones — a state the wake-word module
still compiles in, just without the RMS silence gate.

`-PvoxSkipNativePrep` drops exactly two things, `autoCompileWhisper` and `autoCompileLlama`, for a
verification build that only needs to know whether the Kotlin compiles. `copyShippedSchemas` runs
regardless: the schema tests read the assets it generates.

`autoCompileOpenCv` has no such flag and must not get one — `vendor/ppocr-sdk/opencv/java` is
generated by that build, and both `vendor/ppocr-sdk` and `vox-vision` import `org.opencv.*` from it.
Skipping it means those modules don't compile at all. CI caches the output instead, keyed on the
pinned `vendor/opencv` commit, `scripts/build_opencv_android.sh`, **and the NDK version** — native
output built by one NDK is not interchangeable with another's.

---

## Pattern A: Vosk (version-check only)

Vosk (`com.alphacephei:vosk-android`, resolved via JitPack) is consumed as a plain binary dependency —
no source vendored, nothing compiled. Dependabot's Gradle updater doesn't reliably track this
JitPack-style coordinate (confirmed empirically — it has never opened a PR for it), so:

- **`scripts/check_vosk_version.sh`** — queries the JitPack API (with a Maven Central search fallback)
  for the latest published version, compares it against `gradle/libs.versions.toml`, and prints an
  update notice (no automatic change).
- **`autoCheckVosk`** Gradle task — runnable on demand (`./scripts/vox check vosk`),
  deliberately *not* wired into `preBuild`.
- **`.github/workflows/sync-vosk.yml`** — same check weekly (Monday 06:00 UTC); bumps
  `libs.versions.toml`, runs `compileDebugKotlin` + `testDebugUnitTest`, and opens a PR for a
  person to merge. A green build cannot confirm wake-word model loading or recognition accuracy,
  only that Vosk's API surface still resolves — and no unit test loads a native model.

---

## Pattern A: NewPipeExtractor (version-check only)

`com.github.teamnewpipe:NewPipeExtractor` (resolved via JitPack) — YouTube search/extraction, used in
place of a cloud API. Same shape as Vosk: no source vendored, nothing compiled, Dependabot doesn't
reliably track this JitPack-style coordinate either.

- **`scripts/check_newpipe_extractor_version.sh`** — queries the JitPack API (with a GitHub-tags
  fallback, since unlike Vosk this isn't published on Maven Central at all) for the latest tag,
  compares it against `gradle/libs.versions.toml`, and prints an update notice (no automatic change).
- **`autoCheckNewPipeExtractor`** Gradle task — runnable on demand, not wired into `preBuild`.
- **`.github/workflows/sync-newpipe-extractor.yml`** — same check weekly (Tuesday 06:00 UTC); bumps
  `libs.versions.toml`, compiles, unit-tests, and opens a PR for a person to merge. NewPipeExtractor
  breaks when YouTube changes its page structure rather than when its API changes, so a green build
  cannot see the failure mode that actually happens.

---

## Pattern A: onnxruntime-android (version-check only)

`com.microsoft.onnxruntime:onnxruntime-android` (resolved via Maven Central) — the ONNX inference
engine behind Vision's OCR (`vendor/ppocr-sdk`, `vendor/docquad-sdk`) and Commander's OpenWakeWord
wake-word detection (`core/wakeword`). No source vendored, nothing compiled locally.

**Two artifacts provide `libonnxruntime.so`, and the version is not free to choose.** `sherpa-onnx`
(Piper TTS) carries its own build of ONNX Runtime inside its AAR rather than declaring this
coordinate, so Commander receives the library twice at the same packaged path. AGP keeps sherpa's
copy — `libsherpa-onnx-jni.so` is linked against it — and the `onnxruntime-android` artifact is
present for its Java API and its `libonnxruntime4j_jni.so` bridge. That bridge resolves its symbols
against whichever runtime is packaged, so:

> the version `core/wakeword` declares must equal the version sherpa-onnx bundles.

    sherpa-onnx v1.13.4  →  ONNX Runtime 1.27.0

The two are checked against each other by symbol version: the bridge records the version it requires
(`.gnu.version_r`), the runtime records what it exports (`.gnu.version_d`), and a mismatch means the
bridge cannot resolve `OrtGetApiBase` — `ai.onnxruntime` then fails to load and the wake word never
runs, while every other engine is unaffected. `vox check pairing` reads both out of a built APK, and
runs in CI and before every publish.

A newer release of this artifact is therefore wrong until sherpa-onnx itself moves, and
`.github/dependabot.yml` ignores it for that reason. sherpa's version number says nothing about what
it contains — v1.13.3 bundles 1.24.3, the v1.13.4 patch release bundles 1.27.0 — so the value comes
from reading its binary, not from its tag.

`gradle/libs.versions.toml`'s `onnxruntime` entry is what Vision resolves, where both the runtime and
the bridge come from the same artifact and no such constraint applies.

- No dedicated check/sync script exists for this one (Dependabot's own weekly PRs are sufficient since
  it reliably tracks Maven Central, unlike Vosk/NewPipeExtractor's JitPack coordinates).
- `NativeLibManagerInstrumentedTest` (`vox-vision/src/androidTest`, `vox-commander/src/androidTest`) —
  a real on-device instrumented test that calls `NativeLibManager.init()` and asserts `Status.READY`,
  exercising actual native `.so` loading (a JVM unit test can't — the linker only resolves the real
  files on a real device). Runs weekly in CI: `instrumented-tests.yml` (Wednesday 05:00 UTC) boots
  an x86_64 Android emulator on an ubuntu runner with KVM opened up, executing Commander's
  arm64-v8a libraries through the system image's ARM binary translation. Before the emulator step
  it installs the NDK and CMake via `android-actions/setup-android` and runs
  `./scripts/vox native llama`, so `libllama.so` is in the test APK and `LlamaBridgeSmokeTest` can
  answer whether it executes under translation. Commander only — translation has a fidelity
  ceiling, and Vision's OpenCV load crashes the translated process while passing on real arm64, so
  its connected test runs where real arm64 exists
  (`ANDROID_SERIAL=<arm64 avd> ./gradlew :vox-vision:connectedDebugAndroidTest`). Every
  `release-*.yml` also runs an in-job emulator smoke (`vox check smoke`) before anything publishes.
  Runnable on demand via
  `./gradlew :vox-vision:connectedDebugAndroidTest :vox-commander:connectedDebugAndroidTest` against
  a real device/emulator for extra confidence on a native/ABI-sensitive bump.

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
     the APK via AGP's `packaging.jniLibs.excludes` (reliable for this lib) and download them on demand.
- **`autoCompileWhisper`** Gradle task runs this on every `preBuild`.
- **`.github/workflows/sync-whisper.yml`** — monthly scheduled: bumps the submodule pin to a newer
  stable tag and confirms it still compiles, opening a PR — but deliberately **never** calls
  `publish_whisper_libs.sh` itself. Publishing the production DLC is a manual, human-reviewed step
  after the PR is merged, because CI can only verify "it compiles," never "it still transcribes
  correctly" — that needs an actual on-device sanity check.

### Two libraries, not six

`libwhisper.so` and `libomp.so` are the whole engine. The CMake build sets `BUILD_SHARED_LIBS OFF`,
so ggml is linked into `libwhisper.so`: it defines every ggml symbol it uses, requires none from
outside, and declares no `DT_NEEDED` on a ggml library. Its Vulkan backend is compiled in and binds
to the platform's own `libvulkan.so`; `libomp.so` is its one non-platform dependency.

The list lives in `whisperLibs` (`vox-commander/build.gradle.kts`), and the loader, the packaging
excludes and `publish_whisper_libs.sh` all take it from there — `LibWhisper` returns `false` when a
named file is absent, so the loader and the published set have to agree.

### Addressing the runtime by the commit it was built from

Exclusion is part of the build and always happens; publishing is a person running a script. An
address with no version in it therefore serves whatever was published last, rather than what the APK
was compiled against.

The release is named for the whisper.cpp commit — `whisper-libs-<sha12>`. The build records that
commit into the APK as `assets/whisper-libs.commit`, the app derives its download tag from it, and
`publish_whisper_libs.sh` writes to the same name, so an install can only ask for the build its APK
expects. `./scripts/vox check whisper-published` asks whether that release exists, and every
Commander release runs it before publishing.

Scoped to the commit rather than to the app version so several app versions share one whisper
build. Published releases are permanent: releases accumulate rather than being pruned, and a
published tag's assets are never deleted or replaced, so any address an installed APK carries keeps
resolving.

A build recording no commit falls back to the original `whisper-libs` tag, which is what installs
predating this ask for. The library directory is shared across builds; a `.whisper-commit` marker
records which build its contents came from, and contents whose marker does not match the APK's
recorded commit are replaced at the next download. On upgrade, libraries already on the device are
adopted when they match the recorded digests rather than downloaded again.

### Verifying what is downloaded

`recordWhisperDigests` writes an `assets/whisper-libs.sha256` asset recording what the published
`whisper-libs-<sha12>` release serves, read from GitHub's per-asset digest metadata — not a hash of
the locally compiled libraries, because whisper.cpp does not build reproducibly across toolchains and
what an install downloads is the published set, whichever machine built this APK. When no release
exists for the pin (a checkout mid-bump), the local build is hashed as a fallback and the release
workflow refuses to publish in that state; it also compares the APK's recorded digests against the
release's before publishing. The digests sit inside the APK where its signature covers them — a
digest served at runtime from the release the library came from would prove nothing. `WhisperEngineManager` downloads to `.tmp`, checks
the digest, and renames only on a match, which also stops an interrupted transfer leaving a truncated
`.so` that `areLibsDownloaded()` counts as present. A file with no recorded digest is logged and
accepted; a mismatch fails the download.

### Excluding onnxruntime/Vosk/sherpa-onnx (`full` mode only)

`libonnxruntime.so`, `libvosk.so` and `libsherpa-onnx-jni.so` leave the APK
**only in `full` mode**, which is not the default — see `voxDlc` in
[BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md#how-much-ships-inside-the-apk-voxdlc). The default,
`minimal`, keeps all three inside a ~36 MB APK and downloads nothing; `full` produces ~24 MB and
fetches them on the splash.

They are deliberately **not** called "DLC" in that second mode. Unlike Whisper's model download (a
genuine user choice: pick tiny/base/small in Settings), these are mandatory libraries the app cannot
function without — fetched once on first launch with no user involvement, closer to a deferred asset
load than to downloadable *content*. That asymmetry is why `minimal` became the default: excluding
them deferred nothing and turned one install into an install plus a mandatory download that can fail
offline.

These are excluded by AGP's `packaging.jniLibs.excludes`, the same mechanism as Whisper, applied per
variant through `androidComponents.onVariants` in `vox-commander/build.gradle.kts`.

`libsherpa-onnx-c-api.so` and `libsherpa-onnx-cxx-api.so` are excluded too but never
uploaded/downloaded — confirmed via `readelf -d` that `libsherpa-onnx-jni.so` (the one actually
loaded — confirmed via the compiled Java bindings' `loadLibrary` call) only needs
`libonnxruntime.so` externally, making the other two genuinely unused dead weight.

Because they never reach any build output in `full`, the libraries that *are* published are staged
by `:vox-commander:collectDlcLibs`, which copies them out of the resolved dependencies (an
`android-jni` artifact view of `releaseRuntimeClasspath`) into `vox-commander/build/dlc-libs/`. It
runs automatically after `assembleRelease` in `full` mode. Two artifacts provide a
`libonnxruntime.so` — sherpa-onnx's own (~21 MB) and onnxruntime-android's (~28 MB), different
binaries rather than two copies of one — so the task selects by artifact, not by file name, and
fails if the choice is ever ambiguous. Only sherpa's build exports the symbol version
`libsherpa-onnx-jni.so` links against.

#### Where the packaging rules live

Native packaging is configured through `androidComponents.onVariants`, never in a `buildTypes`
block. A `packaging {}` written inside a build type is not scoped to that build type — AGP 9.6.1
applies it to every variant, so a release-only exclusion also strips the library out of debug
builds, and a debug-only `pickFirst` reaches the release variant, where a `pickFirst` for a path
overrides an `exclude` for the same path.

Both apps follow this. Commander's debug variant needs a `pickFirst` for `libonnxruntime.so`, since
two artifacts provide that path, and `minimal` release builds need the same; `full` release builds
exclude the path outright, which drops both copies and makes the `pickFirst` unnecessary.

Vision excludes onnxruntime + OpenCV (the same mandatory-not-user-facing category as Commander's,
above) the same way. It needs no equivalent of `collectDlcLibs`: its DLC libraries are files in
`vox-vision/src/main/jniLibs/` rather than dependency artifacts, so they are on disk for upload
however the APK is packaged.

Vision's debug build keeps all 15 libs; its release drops to 5 in `full` (16 MB) and keeps all 15
in `minimal` (61 MB).

**Known gap (planned, not yet done):** `check_whisper.sh` assumes Vulkan headers
(`vulkan-headers`/`spirv-headers`/`shaderc`) are already installed via Homebrew
(`VULKAN_HEADERS_BASE`/`SPIRV_HEADERS_BASE`/`SHADERC_BASE`, falling back to `/usr/local` if `brew` is
missing entirely) — it doesn't yet check for or install Homebrew/these formulae itself if they're
absent, so a fresh machine needs them installed manually first. Planned follow-up: detect Homebrew is
missing and install it, then `brew install` the three formulae, before invoking CMake.

---

## Pattern B: llama.cpp (vendored unmodified, compiled at build time)

The on-device LLM runtime behind `LocalLlmInterpreter`. Same shape as Whisper.cpp in every
mechanism, differing only where the engine genuinely differs:

- **Submodule:** `vox-commander/src/main/cpp/llama.cpp` (ggml-org/llama.cpp, pinned by commit,
  vendored unmodified — no `patches/`).
- **Build:** `scripts/check_llama.sh` via `autoCompileLlama` (preBuild, skipped by
  `-PvoxSkipNativePrep`). A CMake project of its own at `src/main/cpp/llama-build/` — not an
  `add_subdirectory` in whisper's CMakeLists, because both submodules vendor ggml under identical
  target names and one configure holding both would collide. Flags: `GGML_VULKAN OFF` (CPU only),
  `GGML_OPENMP OFF` (llama's own threadpool; no libomp dependency), `BUILD_SHARED_LIBS OFF`
  (static ggml), `LLAMA_BUILD_COMMON ON` (grammar/chat-template helpers).
- **One library.** `libllama.so` (~4 MB stripped) defines every ggml symbol it uses, exports only
  its `Java_com_voxapps_llamacpp_*` JNI surface (`--exclude-libs,ALL` plus hidden visibility), and
  has no non-platform `DT_NEEDED` at all. `scripts/check_native_pairing.py` holds this property.
- **Distribution:** excluded from every release APK by `androidComponents.onVariants`; published
  by hand as the release `llama-libs-<pin12>` (`scripts/publish_llama_libs.sh`,
  `./scripts/vox release publish-llama-libs`); fetched on demand by `LlamaEngineManager` when a
  local LLM engine is selected, verified against `assets/llama-libs.sha256` recorded into the APK
  by `recordLlamaDigests`, never over a metered connection.
- **The address is a build fingerprint, not the submodule commit.** The pin is
  `scripts/llama_build_pin.sh`: a git hash over the tree state of the llama.cpp submodule gitlink,
  `vox-commander/src/main/cpp/llama_jni.cpp` and `vox-commander/src/main/cpp/llama-build/`, the
  tag taking its first 12 hex digits. Published releases are immutable, and `libllama.so`'s bytes
  come from the submodule *and* the JNI bridge *and* the CMake config, so the address must move
  when any of them does — a pin over the submodule alone cannot represent a bridge or build-config
  change, and "same tag, different bytes" is not representable at all. One script owns the
  computation; `publish_llama_libs.sh`, `check_llama_published.sh`, `recordLlamaDigests` and
  `scripts/tests/run.sh` all consume it, so no two of them can derive different addresses for the
  same tree. Whisper's tag is keyed to its submodule commit alone.
- **Gate:** `./scripts/vox check llama-published` — the release named by the pin must exist and
  hold `libllama.so`; wired into `release-commander.yml` beside the whisper gate, negative-testable
  via `VOX_LLAMA_PIN`.
- **Sync:** `sync-llama.yml` (monthly) proposes upstream `b<number>` release-tag bumps as PRs; it
  compiles and runs unit tests but never publishes — publishing follows a human checking intent
  parsing on a device.
- **The list exists in four pinned copies** — `LlamaEngineManager.LLAMA_LIBS`, `llamaLibs` in
  build.gradle.kts, `LIBS` in the publish script, `LIBS` in the gate script — held equal by
  `scripts/tests/run.sh`, the same net whisper's list sits in.

## Pattern B: OpenWakeWord (vendored + patched)

Full detail already lives in
[`docs/TECHNICAL_DOCUMENTATION.md` §2 "OpenWakeWord Fork & Sync"](TECHNICAL_DOCUMENTATION.md#openwakeword-fork--sync) —
summarized here for completeness alongside its siblings:

| Path | Role |
|---|---|
| `vendor/openwakeword-android-kt` | Git submodule — pristine upstream source at a pinned tag. Reference only, never compiled directly. |
| `core/wakeword/` | Local Gradle module — vendored + patched copy of upstream's `:wakeword` module, compiled into `vox-commander`. |
| `core/wakeword/src/main/kotlin/.../audio/AudioRecorder.kt` | Patched by `0001` — an RMS silence gate, layered with an adaptive noise-floor margin (`:core:audio`'s `AdaptiveNoiseGate`, also shared by the Vosk engine). |
| `core/wakeword/src/main/kotlin/.../WakeWordEngine.kt` | Patched by `0002` — forwards the gate params through to `AudioRecorder`. |
| `core/wakeword/src/main/kotlin/.../audio/AudioProcessor.kt` | Patched by `0003` — logs a prediction score only above 0.05 (near misses, not a line per inference) and formats it with `Locale.US`. |
| `core/wakeword/patches/*.patch` | The three patches as real unified diffs — regenerate them all with `./scripts/vox patches regen wakeword`. |
| `core/wakeword/NOTICE` / `LICENSE` | Apache-2.0 attribution chain. |

- **`scripts/check_openwakeword_version.sh`** — non-destructive dry-run: is a newer upstream tag
  available, and would each stored patch still `git apply --check` cleanly against it?
- **`autoCheckOpenWakeWord`** Gradle task — runnable on demand, **not** wired into `preBuild`. Its
  dry-run swaps upstream files into the working tree, which no build may do.
- **`.github/workflows/sync-openwakeword.yml`** — weekly: bumps the submodule, fully re-vendors
  `core/wakeword`'s sources, tries to `git apply` each stored patch, and if they all apply cleanly
  *and* the module compiles + unit tests pass, opens a PR that's already ready to merge. Only
  surfaces a manual-merge PR if a patch genuinely conflicts. It never auto-merges.

---

## Pattern B: OpenCV (vendored unmodified, compiled at build time)

`vendor/opencv` is a **git submodule** pointing at `opencv/opencv` directly, pinned to tag `5.0.0`,
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
  - Copies `libopencv_java5.so` (the `.so` name carries OpenCV's major version — this is what
    ppocr-sdk's patch `0004` loads) + the per-module shared libs it dynamically links against
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
    output files happen to already exist.
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
| `vendor/ppocr-sdk/patches/*.patch` | The four patches as real unified diffs — regenerate with `./scripts/vox patches regen ppocr-sdk`. |
| `vendor/ppocr-sdk/NOTICE` | Apache-2.0 attribution + provenance, and what each patch is for. |
| `vendor/ppocr-sdk/opencv/` | *Not* part of this vendoring — OpenCV's build output, see the section above. Gitignored separately. |

**The four patches:**

| Patch | Files | Why |
|---|---|---|
| `0001-load-models-from-bytes` | `ORTSessionManager`, `ModelConfig`, `OCREngine`, `PaddleOCR` | Upstream only ever reads model/config files from `context.assets`. Vision downloads its OCR models at runtime into app-internal storage, so this adds purely-additive overloads (`loadModels(ByteArray, ByteArray)`, `ModelConfig.parse(String, sourceLabel)`, `OCREngine`'s `detModelBytes`/`recModelBytes`/`recConfigContent`, `PaddleOCR.create(..., detModelFile, recModelFile, recConfigFile)`). Every original asset-path method is untouched. |
| `0002-opencv5-geometry-api` | `DBPostProcessor`, `QuadTextCrop` | Upstream targets OpenCV 4.x, where `minAreaRect` and `getPerspectiveTransform` live on `Imgproc`. OpenCV 5.0 moved both to `org.opencv.geometry.Geometry`. |
| `0003-imageutils-manual-resize` | `ImageUtils` | `Imgproc.resize` has a confirmed native SIGSEGV in our OpenCV build (SEGV_ACCERR at a tagged-pointer address — Scudo catching an out-of-bounds read), reproduced on-device and unaffected by `Core.setUseOptimized(false)`. Does the resize by hand over the Mat's raw bytes instead. |
| `0004-opencv5-library-name` | `OpenCVUtils` | `System.loadLibrary("opencv_java5")`, not `opencv_java4`. |

Three of the four exist only because we build OpenCV ourselves. Only `0002` would fail to compile if
it were lost; `0003` and `0004` would ship as an intermittent native crash and an OpenCV that never
initialises — which is why the invariant below is checked rather than assumed.

- **`./scripts/vox patches regen ppocr-sdk`** — regenerates every patch from the current state of
  `vendor/ppocr-sdk/` vs. the pinned `vendor/paddleocr-upstream` submodule; sanity-checks each by
  re-applying it to a copy of the pristine files and confirming it reproduces the current patched
  source byte-for-byte. Which files a patch covers is read out of the patch (`git apply --numstat`),
  so adding a patch to the folder is the whole of adding a patch.
- **`scripts/check_ppocr_sdk_version.sh`** — non-destructive dry-run: has upstream's default branch
  moved past the pinned commit, and would each stored patch still apply cleanly against the newer
  tree? Reported per patch, so a conflict names the one that needs attention.
**Not upstream's tip.** This is the only sync that follows a default branch rather than a tag, so
nobody upstream ever decides a commit is ready — the workflow imposes that wait itself and takes the
newest commit **at least `STALENESS_FLOOR_DAYS` (7) old**. A bad or hostile commit is usually
reverted upstream well inside a week, and being seven days behind an OCR SDK costs nothing. The PR
also names any file upstream **added or removed**, because a re-vendor is a wholesale copy of
someone else's tree reviewed by a human reading a diff, and an added file is what that reading is
least likely to catch.

- **`.github/workflows/sync-ppocr-sdk.yml`** — weekly (Friday): re-clones `vendor/paddleocr-upstream`
  sparse + shallow at that eligible commit, fully re-vendors
  `vendor/ppocr-sdk/src/main/java/com/paddle` from it, applies every patch in `patches/` in name
  order, and if they all apply cleanly *and* the module compiles, opens a PR that's already ready to
  merge. Any patch failing marks the whole step conflicted — a partly patched tree isn't something to
  hand to a build and call clean. It never auto-merges. Unlike OpenWakeWord's default-branch tags,
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
  work: re-vendor + re-apply + build/test + open a PR).

  **Nothing auto-merges.** A green build is a weak claim for these dependencies — no unit test loads
  a native model, so it says Vosk's API still resolves rather than that recognition still works, and
  NewPipeExtractor breaks when YouTube changes its page structure, which a compile cannot see at
  all. They also merged *seconds* after opening the PR, before CI could report on it.
- **NOTICE** file per vendored module: upstream attribution, the exact pinned commit/tag, what the
  local patch does and why, and pointers to the check/regen scripts.
- **One patch per concern, never one combined patch.** `git apply` is all-or-nothing, so a monolith
  means an upstream change conflicting with any single hunk drops *every* adaptation, including ones
  unrelated to the conflict. Separate patches also let the check scripts name which one needs
  attention, and let a patch be deleted on its own when upstream absorbs it.

### Where the toolchain comes from

`scripts/lib/common.sh` resolves it rather than assuming a location, because these scripts run on a
developer's machine as much as on a runner — `vox-commander`'s `preBuild` calls `check_whisper.sh` on
every build:

| resolver | order |
|---|---|
| `vox_android_sdk` | `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then the macOS, Linux and Windows defaults |
| `vox_android_ndk` | `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, newest under `$SDK/ndk`, `ndk-bundle` |
| `vox_prefix_for` | Homebrew, the tool's own location, then the usual prefixes |
| `vox_sha256` | coreutils or macOS |

`vox_android_ndk` follows symlinks: a runner's SDK can be assembled from them.

Build hosts: macOS and Linux directly, Windows through WSL — the scripts are bash.

### Sync schedule

One bot per day, not five at once. Sharing a slot would put five jobs on runners together —
`sync-opencv` building OpenCV from source, `sync-ppocr-sdk` building OpenCV *and* compiling Vision,
`sync-openwakeword` compiling Commander — and, since nothing auto-merges, produce up to five PRs in
one morning, each triggering its own CI run.

| Day (06:00 UTC) | Bot |
|---|---|
| Monday | `sync-vosk` |
| Tuesday | `sync-newpipe-extractor` |
| Wednesday | `sync-openwakeword` |
| Thursday | `sync-opencv` |
| Friday | `sync-ppocr-sdk` |
| 2nd of the month, 09:00 | `sync-whisper` |
| 3rd of the month, 09:00 | `sync-llama` |

Each has a `concurrency` group with `cancel-in-progress: false`, so a hung native build cannot be
lapped by the next run pushing the same branch, and an in-flight run is never killed mid-push.

**A closed PR stays closed.** Dedup asks for a PR on that branch in *any* state, not just open ones.
Deduping on open PRs only meant closing one without merging — a decision to decline that upstream
version — brought it straight back the following week, identical, so declining was impossible and
the PRs accumulated as permanent noise.

### Applying: three-way, so a near miss is not a total loss

Patches are generated with `git diff --no-index` (`scripts/lib/patches.sh`), which records the `index`
lines a three-way merge needs, and applied with `git apply --3way` after writing each pristine file
into the object database so the merge base resolves. An upstream release that moves lines near an
adaptation then merges; a genuine collision arrives as conflict markers for a person to resolve in
the PR.

Conflicts are detected by looking for unmerged paths, not by the exit code — `git apply --3way`
returns 0 when it leaves markers behind.

### The invariant: vendored source == upstream + patches

`./scripts/vox patches verify` (scripts/verify_vendored_patches.sh) rebuilds each vendored tree from its pinned upstream plus every
patch in `patches/`, and diffs the result against what's committed. Anything left over is a local
edit no patch records. Run it after touching a vendored source tree; `.github/workflows/verify-vendor-patches.yml`
runs it on any push or PR that touches a vendored tree, its patches, or the script.

```
./scripts/vox patches verify              # both forks
./scripts/vox patches verify wakeword     # one of them
```

An edit recorded by no patch is discarded at the next re-vendor, and losing one rarely fails a
build — most adaptations surface only at runtime, as an intermittent native crash or an engine that
never initialises — which is why the invariant is checked rather than assumed.

If the check fails and the change was deliberate, capture it: write the diff as
`patches/000N-<name>.patch`, then run that module's regen script to normalise and verify it.

## Native libraries at runtime (`:core:nativelibs`)

Separate from the build-time dependencies above: these are libraries the apps *load*, from inside
the APK or from this build's own GitHub release.

`:core:nativelibs` holds the one implementation both apps use: per-file retries, atomic `.tmp`
writes, version-scoped directories, and a hard failure on a missing library. Each app declares an
object over it:

```kotlin
object NativeLibManager : NativeLibs(
    tagPrefix = "vision",
    versionName = BuildConfig.VERSION_NAME,
    libs = listOf("libonnxruntime.so", ...),   // in load order — System.load() needs deps first
    bundled = BuildConfig.DLC_MODE == "minimal"
)
```

Call sites are unchanged, and Commander inherited the retries and atomic writes it never had.
`bundled` comes from the build (see `voxDlc` in `BUILD_AND_RELEASE.md`) rather than from probing the
filesystem — probing would turn a packaging bug into a silent download.

#### Loading: ask the linker, never the filesystem

`loadAll` calls `System.loadLibrary` first and falls back to `System.load` of the fetched copy. It
must not decide "is this inside the APK?" by looking for the file, because whether a bundled library
exists *as a file* under `nativeLibraryDir` depends on how the APK was packaged:

| App | `extractNativeLibs` | Bundled libs on disk? |
|---|---|---|
| Commander | `true` (`useLegacyPackaging = true`) | Yes, unpacked into `nativeLibraryDir` |
| Vision | `false` (AGP's default) | **No** — mapped straight out of the APK |

An `exists()` check therefore reports "missing" for a library that is present and perfectly loadable —
for Vision, every bundled library it has. Two further rules apply:

- **Nothing loads these at startup.** `loadAll` throws on a missing library on purpose — a missing
  library otherwise resurfaces as an `UnsatisfiedLinkError` somewhere unrelated — so calling it
  eagerly from `Application.onCreate` killed `full` builds before any UI existed, and the splash that
  would have downloaded them never ran. The splash (`NativeLibs.init`) owns fetching, loading and
  reporting; anything earlier has to be non-fatal.
- **Downloaded libraries are dropped to read-only before loading.** Android warns on every load of a
  writable file — *"This will throw on a future Android version"* — because executable code the
  process can still rewrite is the pattern being closed off. Measured on API 36: `setReadOnly()`
  takes four warnings to zero, and they still load. Whisper's loader does the same, and it matters
  more there: Whisper downloads its libraries in **both** DLC modes, so the default build is on that
  path whenever anyone uses Whisper STT.

#### What arrives is checked against what was built

`:vox-commander:collectDlcLibs` writes a `sha256` for each staged library into
`assets/dlc-libs.sha256`, which is packaged into the APK and therefore covered by its signature.
`NativeLibs` verifies a downloaded file against that record before the `.tmp` rename that publishes
it, so a library failing the check never becomes one the app loads. A mismatch counts as a failed
attempt rather than a hard stop — a truncated transfer fails the same way and is worth retrying.

The digest cannot come from the same release as the library: whoever can substitute one can
substitute the other. The trust anchor is the signed APK, the same reasoning that puts model hashes
inside a signed schema.

A `minimal` build records nothing and downloads nothing; a library with no recorded digest downloads
as before.

#### Where the downloads come from

Every release-asset URL — the DLC libraries, Whisper's engine, the schema repository — is built from
`VoxRepo` in `:core:identity`, the only place the repository is named. Renaming the project, or
pointing a fork elsewhere, is that one file.

`VoxRepo.LEGACY_URL` carries the second URL this repository answers on, `…/VoxCommander`. It exists
solely so an install that persisted that URL counts as following this repository rather than a fork
(see `SchemaSignature.isDefaultRepo`); no URL is ever built from it.

Which libraries, and whether they are optional:

| App | Libraries | Optional? |
|---|---|---|
| Commander | onnxruntime, vosk, sherpa-onnx (33 MB) | **No.** onnxruntime backs OpenWakeWord, vosk the Vosk engines, sherpa Piper. Anyone using a wake word needs one on first launch. |
| Commander | whisper (~107 MB, ggml and the Vulkan backend linked in) | **Yes** — only with Whisper STT, Vulkan variant only where supported. Fetched elsewhere, never bundled. |
| Commander | llama.cpp (~4 MB libllama.so, ggml linked in, CPU backend) | **Yes** — only when a local LLM engine is selected. Fetched from its `llama-libs-<pin12>` release, the pin a build fingerprint over the submodule, JNI bridge and CMake config (`scripts/llama_build_pin.sh`), never bundled. |
| Vision | onnxruntime + the OpenCV set (43 MB) | **No.** Vision is an OCR app and these are what OCR needs. |

## Do the native libraries satisfy each other?

    ./scripts/vox check pairing <apk> [--with-libs <dir>]

Gradle resolves coordinates. It has nothing to say about a library that arrives as a *file* inside
another artifact, which is how `libonnxruntime.so` reaches Commander twice — so a consumer compiled
against one build can be packaged beside a different provider with no conflict to resolve. The result
builds, installs, passes every test, and fails at `dlopen` the first time that feature is used.

`scripts/check_native_pairing.py` reads the ELF structures of every `.so` in an APK and reports:

| finding | read from |
|---|---|
| a required symbol version the packaged provider does not export | `.gnu.version_r` vs `.gnu.version_d` |
| a needed library neither packaged nor provided by Android | `DT_NEEDED` |
| strongly-undefined symbols nothing resolves | `.dynsym` |

The third covers the libraries that carry no version records at all — the whole OpenCV set, where
mixing builds would otherwise be invisible. Weak undefined symbols are excluded: tcmalloc hooks, gcov
stubs and newer-API libc entry points are meant to go unresolved.

Android's own exports come from the NDK sysroot stubs. Without an NDK the check still compares
versions and reports which mode it ran in, rather than treating platform symbols as missing.

`--with-libs` adds libraries that are not in the APK but will be present at run time. A `full` build
excludes its DLC libraries and downloads them at first launch, so the APK alone shows a bridge with
no runtime; the release workflows pass the staged directory so what is checked is what the device
will have.

Where it runs:

- `ci.yml`, after `assembleDebug` — every push and every pull request, all six apps
- each `release-*.yml`, between the build and the publish, ahead of the attestation

## Open items

- **`check_whisper.sh`**'s Homebrew dependency detection (Vulkan headers) doesn't yet install Homebrew
  or the required formulae if missing — see the Whisper.cpp section above. Deliberately deferred.

Every buildable dependency has a scheduled sync workflow — seven `sync-*.yml`, one per upstream.

---

## One entry point

Everything under `scripts/` is reachable through a single dispatcher, so a caller names a command
rather than a file:

```
./scripts/vox                        what exists
./scripts/vox check [name]           has anything upstream moved?
./scripts/vox patches verify [mod]   is a vendored fork upstream + its patches?
./scripts/vox patches regen <mod>    regenerate a fork's patches
./scripts/vox native opencv|whisper|llama   the three build-time compiles
./scripts/vox release package        the APK as published
```

Full command set:

```
./scripts/vox check [name]           has anything upstream moved?
./scripts/vox check pairing <apk>    do an APK's native libraries satisfy each other
./scripts/vox check whisper-published   does the published Whisper runtime match the pin?
./scripts/vox check llama-published  does the published llama runtime match the pin?
./scripts/vox check smoke <apk> <app-id>   does the APK survive a cold launch on the adb device?
./scripts/vox patches verify [mod]   is a vendored fork upstream + its patches?
./scripts/vox patches regen <mod>    regenerate a fork's patches
./scripts/vox release package        the APK as published (honours voxDlc)
./scripts/vox release publish-libs   publish whisper .so as the DLC release
./scripts/vox release publish-llama-libs   publish libllama.so as the pinned llama-libs release
./scripts/vox release readme         regenerate the README release table
./scripts/vox release fdroid         regenerate and push F-Droid metadata
./scripts/vox release sbom <app>     CycloneDX SBOM, including the vendored native sources
./scripts/vox native opencv|whisper|llama   the three build-time compiles
./scripts/vox schemas validate       validate the shipped schema JSON
./scripts/vox schemas sign|verify    sign the schema manifest, or check it
./scripts/vox schemas keygen         create a signing keypair (once, ever)
./scripts/vox schemas hash-models    record each model's sha256 by fetching it once
./scripts/vox test                   test this machinery
```

The workflows and Gradle call it too, so splitting or renaming a script does not edit a workflow.
Each script still runs correctly on its own — the dispatcher routes and documents, it never
initialises anything a child depends on. `scripts/lib/` holds what would otherwise be copied into
every script: colours and logging (`common.sh`, which fourteen scripts each had their own copy of),
the key-directory resolution, and the `--report` contract that lets one check answer both a person
and a workflow.

### The automation has tests

Thirty scripts and twenty workflows gate every release, and the contracts between them break
silently: a script that stops emitting its report, a workflow that stops reading it, a vendored fork
that drifts from its patches. Nothing about that surfaces in an Android build.

`./scripts/vox test` asserts those contracts, and CI runs it in its own fast job ahead of the Android
build:

- every check emits `key=value` on stdout and nothing else — a stray banner goes into
  `$GITHUB_OUTPUT` and fails the step with `Invalid format`
- every check always states `has_update`, including offline or with a submodule missing — answering
  nothing is how a bot retires unnoticed
- the dispatcher routes, and rejects nonsense
- each vendored fork equals upstream + patches, **and the verifier fails when it does not** —
  asserted by planting an unrecorded edit and restoring it
- the schemas match their signed manifest, and verification fails on an edited schema

`shellcheck -x` runs over every script in the same job. It found one real hazard when introduced —
an `rm -rf "$A/$B"` with no guard against either being empty — plus two unchecked `cd` calls.
