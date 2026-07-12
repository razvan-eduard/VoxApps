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
    implementation("androidx.sqlite:sqlite:2.4.0")
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
