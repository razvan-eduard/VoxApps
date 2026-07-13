plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.voxapps.vision"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voxapps.vision"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Without this, onnxruntime-android ships all 4 ABIs (~73MB combined) even though OpenCV/
        // PaddleOCR are only ever built for arm64-v8a — mirrors the same restriction Notes/Expenses/
        // Calendar already apply.
        ndk { abiFilters += "arm64-v8a" }
    }

    // CI-only release signing: RELEASE_KEYSTORE_PATH is only set in the release-*.yml workflows
    // (decoded from a GitHub Actions secret there), so local `./gradlew assembleRelease` without it
    // still produces an unsigned APK exactly as before. Vision has no release-vision.yml workflow yet
    // (unlike its siblings), but this keeps it ready for when one's added.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = "vox-vision"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            packaging {
                jniLibs {
                    excludes += setOf(
                        "lib/arm64-v8a/libonnxruntime.so",
                        "lib/arm64-v8a/libopencv_core.so",
                        "lib/arm64-v8a/libopencv_imgproc.so",
                        "lib/arm64-v8a/libopencv_imgcodecs.so",
                        "lib/arm64-v8a/libopencv_java4.so"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:ipc"))
    implementation(project(":core:logging"))
    implementation(project(":vendor:ppocr-sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Persisted "selected OCR zone" setting.
    implementation(libs.androidx.datastore.preferences)

    // Camera capture for the scan screen.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // DLC native library downloading
    implementation(libs.okhttp)
}
