plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // OpenCV is built from source (scripts/build_opencv_android.sh) rather than depending on the
    // stale, unmaintained com.quickbirdstudios:opencv Maven artifact (last published 2021-09-15,
    // whose prebuilt libopencv_java4.so fails to dlopen on modern Android — missing Bionic libc
    // symbol __sfp_handle_exceptions). The build script drops its output directly here: the native
    // lib under opencv/libs/, the Java bindings source under opencv/java/.
    sourceSets {
        named("main") {
            jniLibs.srcDirs("opencv/libs")
            java.srcDirs("opencv/java")
        }
    }
}

// Builds OpenCV from source (once; skips if already built) before compiling this module — mirrors
// vox-commander's autoCompileWhisper task for the exact same reason (a native dependency this repo
// builds itself rather than trusting a stale prebuilt).
val autoCompileOpenCv = tasks.register<Exec>("autoCompileOpenCv") {
    group = "build"
    description = "Builds OpenCV (core, imgproc, imgcodecs; arm64-v8a) from source if not already built."
    // Must run under bash, not sh — CI runners' /bin/sh is dash, which doesn't support the
    // script's "set -o pipefail" and fails immediately with "Illegal option -o pipefail".
    commandLine("bash", "${project.rootDir}/scripts/build_opencv_android.sh")
}

tasks.named("preBuild") {
    dependsOn(autoCompileOpenCv)
}

dependencies {
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
