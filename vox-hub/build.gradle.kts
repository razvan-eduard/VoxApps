plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.voxapps.hub"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voxapps.hub"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
    }

    // CI-only release signing: RELEASE_KEYSTORE_PATH is only set in the release-*.yml workflows
    // (decoded from a GitHub Actions secret there), so local `./gradlew assembleRelease` without it
    // still produces an unsigned APK exactly as before.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = "vox-hub"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // material-icons-extended alone is an ~87MB unshrunk jar (thousands of icon classes);
            // without R8, every unused one ships in the APK — this is what made a dependency-light
            // app like Hub balloon to 46MB despite having no Room/SQLCipher/native libs.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
    implementation(project(":core:design"))
    implementation(project(":core:ipc"))
    implementation(project(":core:logging"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
}
