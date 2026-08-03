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
    // vox-vision's own onnxruntime-android pin — those are two independent constraints that happen
    // to currently disagree (vox-vision is pinned to 1.21.1 because upstream's own 1.27.0/1.28.0
    // builds are broken; sherpa-onnx bundles its own separately-built 1.27.0-tagged binary that is
    // not the same artifact).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
}
