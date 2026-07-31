plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
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

    // The durable pending-request queue (PendingLlmRequestEntity/Dao/VoxLlmRequestQueue) — each
    // consuming app's own @Database includes the entity/DAO directly, generating the Dao impl
    // wherever that @Database is compiled; this module itself has no @Database of its own.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // org.json ships in android.jar at compile time; unit tests need the real implementation
    // (the android.jar stub throws "Stub!"), mirroring vox-commander's test setup.
    testImplementation(libs.junit)
    testImplementation("org.json:json:20260719")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
