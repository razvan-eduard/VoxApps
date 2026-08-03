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
        versionCode = 14
        versionName = "0.14"
        // Without this, onnxruntime-android ships all 4 ABIs (~73MB combined) even though OpenCV/
        // PaddleOCR are only ever built for arm64-v8a — mirrors the same restriction Notes/Expenses/
        // Calendar already apply.
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI-only release signing: RELEASE_KEYSTORE_PATH is only set in the release-*.yml workflows
    // (decoded from a GitHub Actions secret there), so local `./gradlew assembleRelease` without it
    // still produces an unsigned APK exactly as before.
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
        release {
            // material-icons-extended alone is an ~87MB unshrunk jar (thousands of icon classes);
            // without R8, every unused one ships in the APK — this is why the DEX alone was ~53MB
            // (56.7MB total APK). Was disabled while debugging an UnsatisfiedLinkError in the
            // OpenCV 5.0 upgrade (see commit 9e12b6e) that turned out to be an unrelated redundant
            // System.loadLibrary() call, already removed — proguard-rules.pro already keeps
            // org.opencv/ai.onnxruntime/com.paddle.ocr, same as it did before this was disabled.
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
                        // OpenCV 5.0 split geometric algorithms out of imgproc into a new
                        // opencv_geometry module (which itself needs opencv_flann) — both are
                        // pure transitive runtime deps now, confirmed via `readelf -d`:
                        // imgproc's NEEDED includes libopencv_geometry.so -> libopencv_flann.so.
                        "lib/arm64-v8a/libopencv_geometry.so",
                        "lib/arm64-v8a/libopencv_flann.so",
                        // libopencv_java5.so's own NEEDED entries (readelf -d) list these three
                        // directly, even with calib3d/features2d disabled at build time — OpenCV
                        // 5's java bindings link them unconditionally, no APIs of ours use them.
                        "lib/arm64-v8a/libopencv_features.so",
                        "lib/arm64-v8a/libopencv_ptcloud.so",
                        "lib/arm64-v8a/libopencv_stereo.so",
                        // .so name encodes OpenCV's major version (java4 for 4.x, java5 for 5.x —
                        // see scripts/build_opencv_android.sh).
                        "lib/arm64-v8a/libopencv_java5.so"
                    )
                }
            }
        }
        debug {
            isMinifyEnabled = false
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
    // ML-based document corner detector (see vendor/docquad-sdk/NOTICE) — brings in
    // ai.onnxruntime.* transitively; libonnxruntime.so itself is already excluded from packaging
    // above and loaded via NativeLibManager's DLC download instead, same as ppocr-sdk's own use of
    // this artifact.
    implementation(project(":vendor:docquad-sdk"))
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

    testImplementation(libs.junit)

    // Instrumented tests — real on-device runs, needed for NativeLibManagerInstrumentedTest to
    // catch native-linking regressions (UnsatisfiedLinkError etc.) that a JVM-only unit test or a
    // plain compile can never observe (see docs/BUILD_TIME_DEPENDENCIES.md's onnxruntime-android
    // section).
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
