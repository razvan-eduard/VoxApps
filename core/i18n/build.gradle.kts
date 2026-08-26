plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.voxapps.i18n"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:logging"))
    testImplementation(libs.junit)
    // org.json ships in android.jar at compile time; unit tests need the real implementation
    testImplementation("org.json:json:20260719")
}
