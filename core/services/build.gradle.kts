plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.voxapps.services"
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
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

// SchemaSigningKeyTest reads remote-schemas/signing-key.pub — a file outside this module, so Gradle
// cannot see it as an input on its own and calls the test task up to date when only that file
// changes. Which is precisely the change the test exists to catch: rotate the key, and the check
// that would have told you the embedded constant no longer matches simply does not run.
tasks.withType<Test>().configureEach {
    inputs.file("${rootDir}/remote-schemas/signing-key.pub").withPathSensitivity(PathSensitivity.RELATIVE)
}
