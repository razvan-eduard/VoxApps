plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.voxapps.ipc"
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
    implementation(libs.kotlinx.coroutines.android)

    // org.json ships in android.jar at compile time; unit tests need the real implementation
    // (the android.jar stub throws "Stub!"), mirroring vox-commander's test setup.
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
