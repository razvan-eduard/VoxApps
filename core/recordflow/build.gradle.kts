plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.voxapps.recordflow"
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
    // The bus a satellite's prompt travels on. This module decides *whether* to ask and routes the
    // answer back; it never composes a prompt and never reads one.
    implementation(project(":core:ipc"))
    implementation(project(":core:logging"))
    // The card that configures a flow is drawn with the same section card every settings screen uses.
    implementation(project(":core:design"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
