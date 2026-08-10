plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/*
 * How much of the native payload leaves the APK — see `voxDlc` in gradle.properties.
 *
 * One source for two decisions that must agree: whether the release strips these libs out, and
 * whether the app downloads them at first launch. Split across a build script and a Kotlin
 * constant, they drift into an APK missing libs nothing fetches, or an APK carrying libs it
 * downloads again anyway.
 *
 * Whisper is unaffected. It is excluded in both modes because it is the one payload that is
 * genuinely optional: ~193MB that a Vosk or cloud user never needs.
 */
val dlcMode = (project.findProperty("voxDlc") as String?) ?: "minimal"
require(dlcMode in setOf("minimal", "full")) { "voxDlc must be 'minimal' or 'full', got '$dlcMode'" }


android {
    namespace = "com.voxapps.commander"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.voxapps.commander"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "0.16-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    // CI-only release signing: RELEASE_KEYSTORE_PATH is only set in release-commander.yml (decoded
    // from a GitHub Actions secret there), so local `./gradlew assembleRelease` without it still
    // produces an unsigned APK exactly as before.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                // Shared across every vox-* app so their signature-level custom permissions
                // (com.voxapps.vox.permission.*) and first-party IPC routing check
                // (PackageManager.checkSignatures()) actually match in release builds — each app
                // previously used its own distinct per-app alias, which are unrelated keys even
                // within the same keystore file, breaking both mechanisms silently until release
                // APKs were installed side-by-side for the first time.
                keyAlias = "vox-apps"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // core:wakeword's onnxruntime-android dependency and sherpa-onnx's own AAR (which
            // bundles its own separate, independently-built libonnxruntime.so — not resolved via
            // the onnxruntime-android Maven coordinate at all) collide as two sources for the same
            // path — pickFirst avoids a build failure. Scoped to debug only: release excludes
            // libonnxruntime.so entirely (DLC'd instead), and a pickFirst for a path that's also
            // excluded silently wins over the exclude, which was quietly keeping the 28MB lib
            // bundled in "release" despite the exclude rule below appearing to remove it.
            packaging {
                jniLibs {
                    pickFirsts += setOf(
                        "lib/arm64-v8a/libonnxruntime.so",
                        "lib/armeabi-v7a/libonnxruntime.so",
                        "lib/x86/libonnxruntime.so",
                        "lib/x86_64/libonnxruntime.so",
                    )
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Exclude Whisper native libs from release APK — they're downloaded on demand as DLC.
            // This reduces the APK from ~166MB to ~40MB. Debug builds keep them for normal dev workflow.
            //
            // Other large DLC'd libs (onnxruntime, Vosk, litertlm-android, sherpa-onnx) are
            // deliberately NOT excluded here via packaging.jniLibs.excludes, even though they're
            // real and sizable: AGP 9.0.0–9.2.1 (every currently published 9.x release) has
            // confirmed-unreliable arm64-v8a native-lib packaging behavior for this project's
            // dependency set — excludes for these either get silently ignored or the merge output
            // varies between otherwise-identical clean builds (see docs/BUILD_TIME_DEPENDENCIES.md
            // for the full investigation). Instead, release-commander.yml strips them directly from
            // the already-built APK zip (scripts/strip_dlc_libs.sh) — a plain file operation that
            // isn't subject to AGP's merge-time bug — then re-signs. Local `assembleRelease` runs
            // (this file alone) therefore still produce a fully-bundled ~40MB APK; the ~16MB
            // stripped+DLC'd APK only exists as a CI release artifact.
            packaging {
                jniLibs {
                    excludes += setOf(
                        "lib/arm64-v8a/libwhisper.so",
                        "lib/arm64-v8a/libggml-vulkan.so",
                        "lib/arm64-v8a/libggml.so",
                        "lib/arm64-v8a/libggml-base.so",
                        "lib/arm64-v8a/libggml-cpu.so",
                        "lib/arm64-v8a/libomp.so"
                    )
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        disable += "UnprotectedBroadcastReceiver"
    }
    buildFeatures {
        compose = true
        // NativeLibManager builds its DLC download URL from BuildConfig.VERSION_NAME — a
        // compile-time constant that can't disagree with the running build (see its doc comment).
        buildConfig = true
    }

    defaultConfig {
        // Read by NativeLibManager, so the runtime cannot disagree with how the APK was packaged.
        buildConfigField("String", "DLC_MODE", "\"$dlcMode\"")
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += "META-INF/jpms.args"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        noCompress += ".onnx"
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:apppicker"))
    implementation(project(":core:location"))
    implementation(project(":core:backup"))
    implementation(project(":core:ipc"))
    implementation(project(":core:logging"))
    implementation(project(":core:nativelibs"))
    implementation(project(":core:services"))
    implementation(project(":core:preferences"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Security, Navigation, JSON
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.gson)

    // Generic LLM hook background work (survives OEM/Doze restrictions a plain Service doesn't)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.vosk.android)
    implementation(libs.jsoup)
    implementation(libs.litertlm.android)
    implementation(libs.androidx.media)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.reorderable)
    // Chrome Custom Tabs for Spotify dashboard setup
    implementation("androidx.browser:browser:1.10.0")
    // Spotify App Remote SDK (local AAR)
    implementation(files("libs/spotify-app-remote.aar"))
    // Gemini Nano (Google AI Edge) - System LLM SDK
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    // Porcupine Wake Word Engine (Picovoice)
    implementation("ai.picovoice:porcupine-android:4.0.2")
    // OpenWakeWord (fully open-source, ONNX-based wake word detection) — local fork with an RMS
    // silence gate patch (see core/wakeword/NOTICE); pristine upstream kept at
    // vendor/openwakeword-android-kt for sync (scripts/check_openwakeword_version.sh).
    // core:wakeword already declares onnxruntime-android directly (pinned there, independent of
    // vox-vision's own gradle/libs.versions.toml pin — see core/wakeword/build.gradle.kts for why
    // it must match sherpa-onnx's bundled copy instead) — vox-commander's own source never imports
    // ai.onnxruntime.* directly, so a second direct declaration here was a redundant duplicate
    // dependency, not a real requirement. Two sources contributing the same native libs is exactly
    // the kind of ambiguity that made libonnxruntime.so's arm64-v8a packaging/exclude behavior
    // unreliable (see release excludes above) — removing the duplicate leaves a single Maven-
    // resolved source (sherpa-onnx's AAR still separately bundles its own copy of the same path,
    // and currently wins the merge — see the pickFirst comment above).
    implementation(project(":core:wakeword"))
    implementation(project(":core:audio"))
    // Piper TTS via sherpa-onnx (on-device neural TTS)
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
    // Apache Commons Compress for .tar.bz2 extraction (Piper voice models)
    implementation("org.apache.commons:commons-compress:1.28.0")
    // NewPipe Extractor — YouTube search & video URL parsing (replaces Piped API dependency).
    // JitPack coordinate, same as Vosk — version pinned in gradle/libs.versions.toml, checked weekly
    // by scripts/check_newpipe_extractor_version.sh / .github/workflows/sync-newpipe-extractor.yml.
    implementation(libs.newpipe.extractor)
    // ProcessPhoenix — reliable app restart (handles process kill + relaunch)
    implementation("com.jakewharton:process-phoenix:3.0.0")
    // Home-screen widget (Jetpack Glance — current best practice over raw RemoteViews/AppWidgetProvider).
    // GlanceTheme itself lives in the base :glance artifact (a transitive dep of glance-appwidget),
    // already gets Material You dynamic color for free — no separate glance-material3 dependency needed.
    implementation(libs.androidx.glance.appwidget)
    // STT Engines (Whisper.cpp integration)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("androidx.test:core:1.7.0")
    // Real org.json for JVM unit tests — the android.jar stub throws "Stub!",
    // which blocks testing code that parses JSON via org.json (e.g. TextNormalizer, WakeWordProfile).
    testImplementation("org.json:json:20260719")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Înregistrează o sarcină de execuție pentru scriptul Bash Whisper
val autoCompileWhisper = tasks.register<Exec>("autoCompileWhisper") {
    group = "build"
    description = "Verifică upstream-ul whisper.cpp și recompilează prin CMake dacă este necesar."
    
    commandLine("bash", "${project.rootDir}/scripts/vox", "native", "whisper")
}

// Înregistrează o sarcină de execuție pentru verificarea versiunii Vosk
val autoCheckVosk = tasks.register<Exec>("autoCheckVosk") {
    group = "verification"
    description = "Verifică dacă a apărut o versiune mai nouă de Vosk pe JitPack."

    // bash, not sh: the script is a bash script (uses ==, [[ ]]) — same class of bug as the
    // build_opencv_android.sh fix (sh on Ubuntu runners is dash, which doesn't support these).
    commandLine("bash", "${project.rootDir}/scripts/vox", "check", "vosk")
}

// Înregistrează o sarcină de execuție pentru verificarea versiunii NewPipeExtractor
val autoCheckNewPipeExtractor = tasks.register<Exec>("autoCheckNewPipeExtractor") {
    group = "verification"
    description = "Verifică dacă a apărut o versiune mai nouă de NewPipeExtractor pe JitPack."

    // bash, not sh: the script uses [[ ]] (same class of bug as the build_opencv_android.sh fix —
    // sh on Ubuntu runners is dash, which doesn't support bashisms like [[ ]]).
    commandLine("bash", "${project.rootDir}/scripts/vox", "check", "newpipe-extractor")
}

// Verifică dacă fork-ul local OpenWakeWord (core/wakeword) a rămas în urma tag-urilor upstream
val autoCheckOpenWakeWord = tasks.register<Exec>("autoCheckOpenWakeWord") {
    group = "verification"
    description = "Verifică dacă submodulul OpenWakeWord a rămas în urma unui tag upstream nou."

    // bash, not sh: the script uses a bash array (PATCHES=(...)) — same class of bug as the
    // build_opencv_android.sh fix (sh on Ubuntu runners is dash, which doesn't support this).
    commandLine("bash", "${project.rootDir}/scripts/vox", "check", "openwakeword")
}

// Every schema the family ships lives in one folder at the repo root, and the whole folder is
// copied into assets at build time. A list of file names used to live here, and adding a schema
// meant remembering to add it — the folder is the list now.
val copyShippedSchemas = tasks.register<Copy>("copyShippedSchemas") {
    group = "build"
    description = "Copies this app's schemas (and any shared ones) into src/main/assets/schemas/"
    from("${project.rootDir}/remote-schemas/commander") { include("*.json") }
    from("${project.rootDir}/remote-schemas/shared") { include("*.json") }
    // The signed manifest travels with the app so a *fresh* install has a rollback floor. Without
    // it lastSerial starts at zero and a first launch would accept any old, validly-signed manifest
    // — rollback protection would only ever protect installs that had already seen something newer.
    from("${project.rootDir}/remote-schemas") { include("manifest.json") }
    into("${projectDir}/src/main/assets/schemas")
}

// One command for "has anything upstream moved?", across every vendored and pinned dependency —
// not just Commander's three. Same scripts the sync workflows call.
//
//     ./gradlew :vox-commander:checkUpstream
//
// On demand only, for the reason spelled out below.
tasks.register<Exec>("checkUpstream") {
    group = "verification"
    description = "Ask every upstream (Vosk, NewPipe, OpenWakeWord, OpenCV, PaddleOCR, whisper) whether it has moved."
    commandLine("bash", "${project.rootDir}/scripts/vox", "check")
}

// The three autoCheck* tasks above are deliberately NOT wired into preBuild.
//
// "A newer Vosk exists" is a maintenance fact, not a build fact, and it already has a home: the
// weekly sync-*.yml workflows open a PR when an upstream actually moves. Delivering it a second
// time as a warning in every build bought nothing and cost three network round-trips per build,
// builds that behave differently offline, and — the one that mattered — a version check that
// overwrites vendored source files to dry-run a patch against upstream while the build is running.
// Nothing attached to a compile should be writing to the source tree.
//
// They remain runnable on demand, which is how a maintenance task should be reached:
//     ./gradlew :vox-commander:autoCheckVosk
//     ./scripts/check_openwakeword_version.sh
val skipNativePrep = providers.gradleProperty("voxSkipNativePrep").isPresent

// The APK that ships is not the one assembleRelease produces: the DLC libs are stripped out of the
// built zip afterwards (see the release packaging comment above for why AGP can't be trusted to
// exclude them). That stripping used to exist only inside release-commander.yml, so a locally built
// release APK bundled every lib and the DLC download path could not be exercised on a real device
// at all — which is how two bugs in it reached users. This runs the same script CI runs.
//
//     ./gradlew :vox-commander:packageReleaseApk
//
// Needs RELEASE_KEYSTORE_PATH and RELEASE_KEYSTORE_PASSWORD, same as any signed local build.
tasks.register<Exec>("packageReleaseApk") {
    group = "build"
    // The script strips only in `full`; passing the mode keeps a local package consistent with how
    // the APK was actually built.
    environment("VOX_DLC", dlcMode)
    description = "assembleRelease, then strip the DLC libs and re-sign — the APK as published."
    dependsOn("assembleRelease")

    // Captured at configuration time: reading `project` inside doFirst is unsupported with the
    // configuration cache, which is on for this build.
    val packagingScript = "${project.rootDir}/scripts/package_commander_release.sh"
    val keystore = System.getenv("RELEASE_KEYSTORE_PATH") ?: ""
    val password = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: ""
    val outDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile

    // A local assembleRelease signs the APK when the keystore env vars are set and leaves it
    // "-unsigned" when they are not; CI is always the latter. Take whichever exists.
    doFirst {
        val candidates = listOf(
            File(outDir, "vox-commander-release.apk"),
            File(outDir, "vox-commander-release-unsigned.apk")
        )
        val input = candidates.firstOrNull { it.isFile }
            ?: throw GradleException("No release APK in $outDir — did assembleRelease run?")
        if (keystore.isEmpty() || password.isEmpty()) {
            throw GradleException(
                "RELEASE_KEYSTORE_PATH and RELEASE_KEYSTORE_PASSWORD must be set — see docs/BUILD_AND_RELEASE.md"
            )
        }
        commandLine(
            "bash", packagingScript,
            input.absolutePath,
            File(outDir, "VoxCommander-release-stripped.apk").absolutePath,
            keystore, password
        )
    }
    // Replaced in doFirst once the real input is known; Exec requires something here at configure time.
    commandLine("true")
}

tasks.named("preBuild") {
    // Whisper stays: unlike the checks, it produces build output — the .so files this app links.
    // `-PvoxSkipNativePrep` drops it for a verification build that only needs to know whether the
    // Kotlin compiles, and would otherwise need the submodule, the NDK, shaderc and an SDK symlink
    // to reach the same answer.
    if (!skipNativePrep) {
        dependsOn(autoCompileWhisper)
    }
    // Not optional anywhere: the shipped schemas are generated into assets, and the tests that
    // check code against them read the generated copy.
    dependsOn(copyShippedSchemas)
}

// A handful of ViewModel tests use viewModelScope.launch{} (not tied to the test's own TestScope),
// so a coroutine can still be in flight when that test's own @After tears down Dispatchers.Main —
// then resume later during a DIFFERENT test class sharing the same JVM and blow up there instead
// (surfaces as "UncaughtExceptionsBeforeTest" on an unrelated test). Forking a fresh JVM per test
// class eliminates this whole category of cross-class leakage without auditing every test file.
tasks.withType<Test> {
    forkEvery = 1
}

// Some tests read files the compiler never sees, so nothing else would make Gradle re-run them when
// those files change. The schemas are named at their source rather than in assets: assets/schemas is
// this build's own output, and a task cannot sensibly treat another task's output as its input.
tasks.withType<Test>().configureEach {
    inputs.file("proguard-rules.pro").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("${project.rootDir}/remote-schemas").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/assets/translations").withPathSensitivity(PathSensitivity.RELATIVE)
}
