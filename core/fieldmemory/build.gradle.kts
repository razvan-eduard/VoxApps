plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voxapps.fieldmemory"
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
    // The diff/apply text logic stays in :core:textmatch; this module owns the stored memory —
    // what has been learned, how many times, and whether it is still trusted.
    implementation(project(":core:textmatch"))
    implementation(project(":core:datahygiene"))

    // LearnedFieldCorrection/Dao — each consuming app's own @Database includes them directly, same
    // pattern as :core:attachments' AttachmentEntity/Dao; this module itself has no @Database.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
