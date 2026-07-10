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
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
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

    // Room + KSP
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Encrypted-at-rest storage: SQLCipher for Room + Keystore-backed passphrase.
    // sqlcipher-android (new edition) ships 16 KB-page-aligned native libs (Play requirement).
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation(libs.androidx.security.crypto)

    // Scheduled Auto-Merge Categories (off/daily/weekly/monthly)
    implementation(libs.androidx.work.runtime.ktx)

    // --- Unit tests (JVM, mirror vox-commander) ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.test:core:1.7.0")
    // org.json ships in android.jar at compile time; unit tests need the real implementation
    // (mirrors :core:ipc, which needed this for the exact same reason).
    testImplementation("org.json:json:20240303")
}
