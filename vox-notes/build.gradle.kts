plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voxapps.notes"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voxapps.notes"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "0.16"
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
    implementation(project(":core:attachments"))
    implementation(project(":core:logging"))
    implementation(project(":core:datahygiene"))
    implementation(project(":core:textmatch"))
    implementation(project(":core:schema-annotations"))
    ksp(project(":core:schema-processor"))
    implementation(project(":core:onboarding"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Preferences (read mode + session timeout)
    implementation(libs.androidx.datastore.preferences)

    // Biometric read-gate (UI-level access gate; DB stays encrypted via Keystore passphrase).
    implementation(libs.androidx.biometric)
    // androidx.biometric:1.1.0 transitively pulls the ancient androidx.fragment:1.2.5, whose strict
    // "lower 16 bits only" requestCode check crashes against the modern ActivityResultRegistry
    // (androidx.activity, pulled in by the Compose stack) when requesting a runtime permission via
    // registerForActivityResult — force a current fragment version so it wins dependency resolution.
    // Mirrors vox-expenses'/vox-calendar's identical fix.
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // Room + KSP
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Encrypted-at-rest storage: SQLCipher for Room + Keystore-backed passphrase.
    // sqlcipher-android (new edition) ships 16 KB-page-aligned native libs (Play requirement).
    implementation("net.zetetic:sqlcipher-android:4.17.0")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation(libs.androidx.security.crypto)

    // Scheduled Auto-Merge Categories (off/daily/weekly/monthly)
    implementation(libs.androidx.work.runtime.ktx)

    // Home-screen widget (Jetpack Glance — current best practice over raw RemoteViews/AppWidgetProvider).
    // GlanceTheme itself lives in the base :glance artifact (a transitive dep of glance-appwidget),
    // already gets Material You dynamic color for free — no separate glance-material3 dependency needed.
    implementation(libs.androidx.glance.appwidget)

    // --- Unit tests (JVM, mirror vox-commander) ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("androidx.test:core:1.7.0")
    // org.json ships in android.jar at compile time; unit tests need the real implementation
    // (mirrors :core:ipc, which needed this for the exact same reason).
    testImplementation("org.json:json:20260719")
}
