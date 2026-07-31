plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.voxapps.voxconnect"
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
    implementation(project(":core:ipc"))
    implementation(project(":core:logging"))
    implementation(libs.kotlinx.coroutines.android)

    // Paired-device store: Keystore-backed EncryptedSharedPreferences, same pattern as vox-hub's
    // SyncPeerStore / each satellite's DbKey.
    implementation(libs.androidx.security.crypto)

    // Embedded HTTP+WebSocket server the bridge runs inside vox-hub's process. CIO over Netty for a
    // lighter footprint appropriate to an embedded mobile server.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    // ZXing: an independent open-source project (Apache 2.0), not a Google product despite the
    // com.google.zxing package name — used here for QR *decoding* (the PC generates/shows the QR,
    // the phone scans it with its own camera, which is more reliably present than a webcam on a
    // desktop PC). Paired with CameraX (already used elsewhere in this monorepo by vox-vision) for
    // camera frames — see VoxConnectQrScanner.kt.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // org.json ships in android.jar at compile time; unit tests need the real implementation
    // (the android.jar stub throws "Stub!"), mirroring core/ipc's own test setup.
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.test)
}
