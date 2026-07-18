plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

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
        versionCode = 6
        versionName = "0.5-beta"

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
            // core:wakeword and vox-commander both declare onnxruntime-android directly, so their
            // native libs collide as two sources for the same path — pickFirst avoids a build
            // failure. Scoped to debug only: release excludes libonnxruntime.so entirely (DLC'd
            // instead), and a pickFirst for a path that's also excluded silently wins over the
            // exclude, which was quietly keeping the 28MB lib bundled in "release" despite the
            // exclude rule below appearing to remove it.
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
            // Other large DLC'd libs (onnxruntime, Vosk, mediapipe-genai, sherpa-onnx) are
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
    implementation(project(":core:ipc"))
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
    implementation(libs.mediapipe.genai)
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
    // core:wakeword already declares onnxruntime-android 1.27.0 (16KB-page-size-aligned, required
    // for Android 15+) — vox-commander's own source never imports ai.onnxruntime.* directly, so a
    // second direct declaration here was a redundant duplicate dependency, not a real requirement.
    // Two sources contributing the same native libs is exactly the kind of ambiguity that made
    // libonnxruntime.so's arm64-v8a packaging/exclude behavior unreliable (see release excludes
    // above) — removing the duplicate leaves a single, unambiguous source.
    implementation(project(":core:wakeword"))
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
    // STT Engines (Whisper.cpp integration)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("androidx.test:core:1.7.0")
    // Real org.json for JVM unit tests — the android.jar stub throws "Stub!",
    // which blocks testing code that parses JSON via org.json (e.g. TextNormalizer, WakeWordProfile).
    testImplementation("org.json:json:20240303")
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
    
    commandLine("sh", "${project.rootDir}/scripts/check_whisper.sh")
}

// Înregistrează o sarcină de execuție pentru verificarea versiunii Vosk
val autoCheckVosk = tasks.register<Exec>("autoCheckVosk") {
    group = "verification"
    description = "Verifică dacă a apărut o versiune mai nouă de Vosk pe JitPack."

    // bash, not sh: the script is a bash script (uses ==, [[ ]]) — same class of bug as the
    // build_opencv_android.sh fix (sh on Ubuntu runners is dash, which doesn't support these).
    commandLine("bash", "${project.rootDir}/scripts/check_vosk_version.sh")
}

// Înregistrează o sarcină de execuție pentru verificarea versiunii NewPipeExtractor
val autoCheckNewPipeExtractor = tasks.register<Exec>("autoCheckNewPipeExtractor") {
    group = "verification"
    description = "Verifică dacă a apărut o versiune mai nouă de NewPipeExtractor pe JitPack."

    // bash, not sh: the script uses [[ ]] (same class of bug as the build_opencv_android.sh fix —
    // sh on Ubuntu runners is dash, which doesn't support bashisms like [[ ]]).
    commandLine("bash", "${project.rootDir}/scripts/check_newpipe_extractor_version.sh")
}

// Verifică dacă fork-ul local OpenWakeWord (core/wakeword) a rămas în urma tag-urilor upstream
val autoCheckOpenWakeWord = tasks.register<Exec>("autoCheckOpenWakeWord") {
    group = "verification"
    description = "Verifică dacă submodulul OpenWakeWord a rămas în urma unui tag upstream nou."

    commandLine("sh", "${project.rootDir}/scripts/check_openwakeword_version.sh")
}

// Copy models.json from repo root into assets (single source of truth in root)
val copyModelsJson = tasks.register<Copy>("copyModelsJson") {
    group = "build"
    description = "Copies models.json from repo root into app/src/main/assets/"
    from("${project.rootDir}/models.json")
    into("${projectDir}/src/main/assets")
}

// Copy search_definitions.json from repo root into assets (single source of truth in root)
val copySearchDefinitions = tasks.register<Copy>("copySearchDefinitions") {
    group = "build"
    description = "Copies search_definitions.json from repo root into app/src/main/assets/"
    from("${project.rootDir}/search_definitions.json")
    into("${projectDir}/src/main/assets")
}

// Copy intents.json from repo root into assets (single source of truth in root)
val copyIntentsJson = tasks.register<Copy>("copyIntentsJson") {
    group = "build"
    description = "Copies intents.json from repo root into app/src/main/assets/"
    from("${project.rootDir}/intents.json")
    into("${projectDir}/src/main/assets")
}

// Copy external_services.json from repo root into assets (single source of truth in root) — listed
// here for visibility/consistency with Commander's other JSON config files even though the actual
// exchange-rate lookup happens in vox-expenses, which consumes its own copy of the same file.
val copyExternalServicesJson = tasks.register<Copy>("copyExternalServicesJson") {
    group = "build"
    description = "Copies external_services.json from repo root into app/src/main/assets/"
    from("${project.rootDir}/external_services.json")
    into("${projectDir}/src/main/assets")
}

// Copy api_integrations.json from repo root into assets (single source of truth in root) — the
// declarative per-service API definitions consumed by ApiIntegrationRegistry/DeclarativeApiExecutor.
val copyApiIntegrationsJson = tasks.register<Copy>("copyApiIntegrationsJson") {
    group = "build"
    description = "Copies api_integrations.json from repo root into app/src/main/assets/"
    from("${project.rootDir}/api_integrations.json")
    into("${projectDir}/src/main/assets")
}

// Forțează procesul de build al aplicației să ruleze aceste scripturi chiar la început
tasks.named("preBuild") {
    dependsOn(autoCompileWhisper)
    dependsOn(autoCheckVosk)
    dependsOn(autoCheckNewPipeExtractor)
    dependsOn(autoCheckOpenWakeWord)
    dependsOn(copyModelsJson)
    dependsOn(copySearchDefinitions)
    dependsOn(copyIntentsJson)
    dependsOn(copyExternalServicesJson)
    dependsOn(copyApiIntegrationsJson)
}

// A handful of ViewModel tests use viewModelScope.launch{} (not tied to the test's own TestScope),
// so a coroutine can still be in flight when that test's own @After tears down Dispatchers.Main —
// then resume later during a DIFFERENT test class sharing the same JVM and blow up there instead
// (surfaces as "UncaughtExceptionsBeforeTest" on an unrelated test). Forking a fresh JVM per test
// class eliminates this whole category of cross-class leakage without auditing every test file.
tasks.withType<Test> {
    forkEvery = 1
}

