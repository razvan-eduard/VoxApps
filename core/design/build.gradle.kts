plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.voxapps.design"
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
    implementation(project(":core:logging"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    // Just the base icon set (Add/Edit/Close/etc) for the shared color picker — not
    // material-icons-extended, which alone is an ~87MB unshrunk jar of icons this module
    // has no other use for.
    implementation("androidx.compose.material:material-icons-core")
    // BackHandler, for the shared DoubleBackToExitHandler composable.
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
