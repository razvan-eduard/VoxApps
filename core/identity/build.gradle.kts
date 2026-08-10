plugins {
    alias(libs.plugins.android.library)
}

// Deliberately dependency-free. Everything that names the repository has to be able to see this,
// including the lowest modules in the graph, so it must not pull anything in behind it.
android {
    namespace = "com.voxapps.identity"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
