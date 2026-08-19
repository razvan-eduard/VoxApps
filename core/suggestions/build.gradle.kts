plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voxapps.suggestions"
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
    implementation(project(":core:design"))
    // FieldWeight — which of a record's fields are coarse enough to be written unread and which are
    // not. Declared here per field; consulted by the flow's levels.
    api(project(":core:recordflow"))

    // FieldSuggestion/Dao — each consuming app's own @Database includes them directly, same pattern
    // as :core:attachments' AttachmentEntity/Dao; this module itself has no @Database.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation("androidx.compose.foundation:foundation")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
