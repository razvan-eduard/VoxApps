plugins {
    alias(libs.plugins.android.library)
}

// Local fork of xyz.rementia:openwakeword (Apache 2.0), vendored + patched — see NOTICE.
// Kept as a plain android-library (no Compose): this module is pure detection logic.
android {
    namespace = "com.rementia.openwakeword.lib"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation(project(":core:audio"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Deliberately NOT the shared gradle/libs.versions.toml catalog alias (unlike vox-vision's own
    // onnxruntime-android usage) — this module compiles into vox-commander, where sherpa-onnx's own
    // AAR (Piper TTS) bundles its own separate, independently-built libonnxruntime.so at the same
    // packaged path, which wins Commander's native-lib merge over this dependency's copy (confirmed
    // via merged_native_libs output — see vox-commander/build.gradle.kts's pickFirst comment). This
    // version must match whatever OrtGetApiBase version *that* winning binary actually exports
    // (currently VERS_1.27.0, confirmed via readelf against sherpa-onnx v1.13.4's bundled copy), not
    // vox-vision's own onnxruntime-android pin — those are two independent constraints (vision
    // rides the shared catalog entry, where runtime and bridge come from the same artifact and no
    // pairing applies; this one is locked to whatever sherpa's AAR bundles).
    // Determined by sherpa-onnx, not chosen here. vox-commander pulls in sherpa-onnx for Piper TTS,
    // whose AAR carries its own build of ONNX Runtime at the same packaged path; that copy is the one
    // AGP keeps, because libsherpa-onnx-jni.so is linked against it. So this artifact is present for
    // its Java API and its libonnxruntime4j_jni.so bridge, and the bridge resolves its symbols
    // against sherpa's runtime — which only works when this version equals the one sherpa bundles.
    //
    //     sherpa-onnx v1.13.4  →  ONNX Runtime 1.27.0
    //
    // A newer release of this artifact is therefore wrong until sherpa itself moves. `vox check
    // pairing` reads both out of a built APK and fails when they disagree, and .github/dependabot.yml
    // keeps the bot from proposing it.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
}
