plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voxapps.attachments"
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
    // VisionAttachmentCapture builds/sends a VoxOcrRequest and starts VisionActivity — this module
    // already owns "how an app adds a photo to a record" and is a dependency of every app, so the
    // shared Vision-launching logic lives here rather than duplicated per app.
    implementation(project(":core:ipc"))
    implementation(project(":core:design"))

    // AttachmentEntity/Dao — each consuming app's own @Database includes them directly, same
    // pattern as :core:ipc's PendingLlmRequestEntity/Dao; this module itself has no @Database.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.activity.compose)

    // Zoomable image viewer for both the thumbnail strip and the full-screen tap-to-zoom dialog —
    // promoted here from being an Expenses-only dependency, since every app using AttachmentsSection
    // needs it now.
    implementation("me.saket.telephoto:zoomable-image-coil:0.19.0")

    testImplementation(libs.junit)
}
