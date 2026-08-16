plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.voxapps.docread"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:logging"))
    api(project(":core:textmatch"))
    // The template library arrives as signed schema, like every other list this reads from.
    api(project(":core:services"))
    // The schema data classes carry Gson annotations, so the library is a compile dependency here.
    implementation(libs.gson)

    // org.json ships in android.jar at compile time; unit tests need the real implementation
    // (the android.jar stub throws "Stub!"), mirroring :core:datahygiene's test setup.
    testImplementation(libs.junit)
    testImplementation("org.json:json:20260719")
    testImplementation(libs.gson)
}
