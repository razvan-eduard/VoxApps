plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.cyclonedx)
}

android {
    namespace = "com.voxapps.hub"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voxapps.hub"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "0.12"
        // No first-party native libs today; pins the ABI so a future transitive dependency with
        // multi-ABI .so files cannot quietly quadruple the APK.
        ndk { abiFilters += "arm64-v8a" }
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
                // Stated rather than defaulted. AGP's default here is v2 alone — while every
                // published release so far is v3. An installed app updates only from an APK signed
                // by the same certificate, so the scheme is not a detail to let a default change.
                // v1 is JAR signing, unnecessary above API 24; minSdk is 29.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // material-icons-extended alone is an ~87MB unshrunk jar (thousands of icon classes);
            // without R8, every unused one ships in the APK — this is what made a dependency-light
            // app like Hub balloon to 46MB despite having no Room/SQLCipher/native libs.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
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
    implementation(project(":core:backup"))
    implementation(project(":core:design"))
    implementation(project(":core:ipc"))
    // For SyncDeltaKeys only: the Hub builds sync deltas itself, so it's a participant in the same
    // wire contract as the satellites' *SyncHandlers and must share their key definitions.
    implementation(project(":core:datahygiene"))
    implementation(project(":core:logging"))
    implementation(project(":core:preferences"))
    implementation(project(":core:voxconnect"))
    // PreviewView for the pairing QR scanner's camera preview (VoxConnectSettingsCard.kt) — the
    // rest of CameraX comes from :core:voxconnect's own (implementation-scoped) dependency.
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20260719")
}
