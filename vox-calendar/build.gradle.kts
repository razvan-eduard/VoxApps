plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    // Distinct from applicationId on purpose — :core:calendar (a direct dependency below) already
    // owns the AGP namespace "com.voxapps.calendar"; reusing it here would generate a duplicate
    // com.voxapps.calendar.R class at dex-merge time.
    namespace = "com.voxapps.calendarapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voxapps.calendar"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
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
            }
        }
    }

    buildTypes {
        release {
            // material-icons-extended alone is an ~87MB unshrunk jar (thousands of icon classes);
            // without R8, every unused one ships in the APK. Shrinking is what actually keeps release
            // builds under IzzyOnDroid's size guideline — per-ABI splitting wouldn't help here since
            // the bulk is DEX bytecode, not native libs.
            isMinifyEnabled = true
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
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:calendar"))
    implementation(project(":core:ipc"))
    implementation(project(":core:logging"))
    implementation(project(":core:textmatch"))
    implementation(project(":core:onboarding"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Preferences (settings + session timeout)
    implementation(libs.androidx.datastore.preferences)

    // Biometric read-gate (UI-level access gate; DB stays encrypted via Keystore passphrase).
    implementation(libs.androidx.biometric)
    // See vox-expenses/vox-notes' identical comment: forces a current fragment version so the
    // biometric prompt's registerForActivityResult doesn't crash against the modern
    // ActivityResultRegistry pulled in by the Compose stack.
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Room + KSP
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Encrypted-at-rest storage: SQLCipher for Room + Keystore-backed passphrase.
    implementation("net.zetetic:sqlcipher-android:4.17.0")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation(libs.androidx.security.crypto)

    // ICS import/export (Phase 5) — pure-Java, few dependencies, Android-compatible.
    implementation("net.sf.biweekly:biweekly:0.6.8")

    // --- Unit tests (JVM, mirror vox-notes/vox-expenses) ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.json:json:20240303")
}
