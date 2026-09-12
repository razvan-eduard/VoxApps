plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.voxapps.applock"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
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
    api(libs.androidx.biometric)
    // androidx.biometric:1.1.0 transitively pulls the ancient androidx.fragment:1.2.5, whose strict
    // "lower 16 bits only" requestCode check crashes against the modern ActivityResultRegistry
    // (androidx.activity, pulled in by the Compose stack) when requesting a runtime permission via
    // registerForActivityResult — force a current fragment version so it wins dependency resolution.
    api("androidx.fragment:fragment-ktx:1.9.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
}
