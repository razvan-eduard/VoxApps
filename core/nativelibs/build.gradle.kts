plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.voxapps.nativelibs"
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
    implementation(libs.okhttp)
}
