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
    implementation(project(":core:services"))
    // api, not implementation: LogsSettingsTab's strings carry a LogViewerStrings from this module,
    // so a caller naming the type needs it on its compile classpath.
    api(project(":core:logging"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    // The base icon set (Add/Edit/Close/etc) for the shared color picker, plus the extended one for
    // the download arrow on a model row — the only glyph here outside the base set. Extended is an
    // ~87MB unshrunk jar, but every app already depends on it and R8 keeps only what is reached,
    // so the cost is the same whether it is declared here or one module further out.
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.material.icons.extended)
    // BackHandler, for the shared DoubleBackToExitHandler composable.
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
