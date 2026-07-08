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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Matches the version vox-commander itself depends on (Piper/sherpa-onnx) — same artifact,
    // one resolved version, no duplicate-class risk.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
}
