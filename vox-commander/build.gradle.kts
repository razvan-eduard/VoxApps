plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voxcommander.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.voxcommander.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Exclude Whisper native libs from release APK — they're downloaded on demand as DLC.
            // This reduces the APK from ~166MB to ~19MB. Debug builds keep them for normal dev workflow.
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
            pickFirsts += setOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/x86/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so",
            )
        }
    }
    androidResources {
        noCompress += ".onnx"
    }
}

dependencies {
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
    implementation("ai.picovoice:porcupine-android:3.0.2")
    // OpenWakeWord (fully open-source, ONNX-based wake word detection)
    implementation("xyz.rementia:openwakeword:0.1.5")
    // Force ONNX Runtime 1.20.1+ for 16KB page size alignment (required for Android 15+)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    // Piper TTS via sherpa-onnx (on-device neural TTS)
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.3")
    // Apache Commons Compress for .tar.bz2 extraction (Piper voice models)
    implementation("org.apache.commons:commons-compress:1.28.0")
    // NewPipe Extractor — YouTube search & video URL parsing (replaces Piped API dependency)
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.24.8")
    // ProcessPhoenix — reliable app restart (handles process kill + relaunch)
    implementation("com.jakewharton:process-phoenix:3.0.0")
    // STT Engines (Whisper.cpp integration)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("app.cash.turbine:turbine:1.1.0")
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
    
    commandLine("sh", "${project.rootDir}/scripts/check_vosk_version.sh")
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

// Forțează procesul de build al aplicației să ruleze aceste scripturi chiar la început
tasks.named("preBuild") {
    dependsOn(autoCompileWhisper)
    dependsOn(autoCheckVosk)
    dependsOn(copyModelsJson)
    dependsOn(copySearchDefinitions)
    dependsOn(copyIntentsJson)
}

