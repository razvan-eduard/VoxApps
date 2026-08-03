plugins {
    alias(libs.plugins.android.library)
}
// Kotlin sources compile without a separate kotlin-android plugin alias — mirrors
// vendor/ppocr-sdk's build.gradle.kts (also plain android.library, also has .kt sources), whatever
// implicitly applies Kotlin support at the root level.

android {
    namespace = "com.voxapps.vision.ml.docquad"
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
}

dependencies {
    implementation(libs.onnxruntime.android)
}
