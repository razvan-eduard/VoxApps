import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.cyclonedx)
}

/*
 * How much of the native payload leaves the APK — see `voxDlc` in gradle.properties.
 *
 * One source for two decisions that must agree: whether these libs are excluded from the APK, and
 * whether the app downloads them at first launch. Vision has no optional payload at all — it is an
 * OCR app and these are what OCR needs — so `minimal` bundles every one of them.
 */
val dlcMode = (project.findProperty("voxDlc") as String?) ?: "minimal"
require(dlcMode in setOf("minimal", "full")) { "voxDlc must be 'minimal' or 'full', got '$dlcMode'" }

/**
 * Excluded from the release APK in `full` and fetched on the splash; bundled in `minimal`.
 *
 * Must stay in step with NativeLibManager.libs — and with release-vision.yml, which uploads these
 * from src/main/jniLibs (they are files in the source set, not dependency artifacts, so they exist
 * on disk for upload no matter how the APK is packaged).
 */
val dlcLibs = listOf(
    "libonnxruntime.so",
    "libopencv_core.so",
    "libopencv_imgproc.so",
    "libopencv_imgcodecs.so",
    // OpenCV 5.0 split geometric algorithms out of imgproc into a new opencv_geometry module (which
    // itself needs opencv_flann) — both are pure transitive runtime deps now, confirmed via
    // `readelf -d`: imgproc's NEEDED includes libopencv_geometry.so -> libopencv_flann.so.
    "libopencv_geometry.so",
    "libopencv_flann.so",
    // libopencv_java5.so's own NEEDED entries (readelf -d) list these three directly, even with
    // calib3d/features2d disabled at build time — OpenCV 5's java bindings link them
    // unconditionally, no APIs of ours use them.
    "libopencv_features.so",
    "libopencv_ptcloud.so",
    "libopencv_stereo.so",
    // .so name encodes OpenCV's major version (java4 for 4.x, java5 for 5.x — see
    // scripts/build_opencv_android.sh).
    "libopencv_java5.so"
)

android {
    namespace = "com.voxapps.vision"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voxapps.vision"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "0.17"
        // Without this, onnxruntime-android ships all 4 ABIs (~73MB combined) even though OpenCV/
        // PaddleOCR are only ever built for arm64-v8a — mirrors the same restriction Notes/Expenses/
        // Calendar already apply.
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
                // Shared across every vox-* app so their signature-level custom permissions
                // (com.voxapps.vox.permission.*) and first-party IPC routing check
                // (PackageManager.checkSignatures()) actually match in release builds — each app
                // previously used its own distinct per-app alias, which are unrelated keys even
                // within the same keystore file, breaking both mechanisms silently until release
                // APKs were installed side-by-side for the first time.
                keyAlias = "vox-apps"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                // Stated rather than defaulted. AGP's default here is v2 alone — while every
                // published release so far is v3. An installed app updates only from an APK signed
                // by the same certificate, so the scheme is not a detail to let a default change.
                // v1 is JAR signing, unnecessary above API 24; minSdk is 29.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // material-icons-extended alone is an ~87MB unshrunk jar (thousands of icon classes);
            // without R8, every unused one ships in the APK — this is why the DEX alone was ~53MB
            // (56.7MB total APK). Was disabled while debugging an UnsatisfiedLinkError in the
            // OpenCV 5.0 upgrade (see commit 9e12b6e) that turned out to be an unrelated redundant
            // System.loadLibrary() call, already removed — proguard-rules.pro already keeps
            // org.opencv/ai.onnxruntime/com.paddle.ocr, same as it did before this was disabled.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Native packaging is NOT configured here — see the androidComponents block below the
            // android {} block for why a `packaging {}` inside a build type does not do what it
            // reads like.
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        buildConfigField("String", "DLC_MODE", "\"$dlcMode\"")
    }

    buildFeatures {
        compose = true
        // NativeLibManager builds its DLC download URL from BuildConfig.VERSION_NAME — a
        // compile-time constant that can't disagree with the running build (see its doc comment).
        buildConfig = true
    }
}

/*
 * Which native libraries each variant packages.
 *
 * This is here, and not in the `release` block, because a `packaging {}` written inside a build type
 * is not scoped to that build type: AGP 9.6.1 applies it to every variant. With `-PvoxDlc=full` the
 * release excludes below were also stripping OpenCV and onnxruntime out of *debug* builds, which
 * left them 5 libs and an UnsatisfiedLinkError away from doing any OCR at all. Only the variant API
 * scopes for real. Same defect, and the same fix, as vox-commander — where it additionally made
 * AGP's excludes look broken and cost a whole post-build stripping subsystem before it was
 * understood (docs/BUILD_TIME_DEPENDENCIES.md).
 *
 * `minimal` excludes nothing: these are ~43MB that every install downloaded on the splash anyway,
 * since Vision is an OCR app and OCR is what these are. Excluding them deferred nothing; it turned
 * one install into an install plus a mandatory download that can fail offline.
 */
androidComponents {
    onVariants { variant ->
        if (dlcMode == "full" && variant.buildType == "release") {
            variant.packaging.jniLibs.excludes.addAll(dlcLibs.map { "lib/arm64-v8a/$it" })

            // The digests of the libraries this build will download go into the APK, where the
            // APK's own signature covers them — a digest fetched from the same place as the
            // library would prove nothing. Wiring them as a generated asset also makes packaging
            // depend on the staging task, so `assembleRelease` produces both the APK and the files
            // to upload beside it, from one selection.
            variant.sources.assets?.addGeneratedSourceDirectory(stageDlcLibs, StageDlcLibs::assetsDir)
        }
    }
}

/*
 * Stages the `full`-mode DLC libraries and records what they hash to. Twin of vox-commander's
 * StageDlcLibs, adapted for how this app sources them: the ten libraries are source-set files
 * written by scripts/build_opencv_android.sh into src/main/jniLibs, not resolved dependency
 * artifacts, so the inputs are those files directly.
 *
 * Two outputs, from one selection, on purpose. The staged `.so` files are uploaded as release
 * assets; the digests are written into the APK's assets so the running app knows what bytes to
 * expect back when it downloads them. Computing them separately would let the published library
 * and the recorded digest come from different files, which is worse than recording nothing.
 */
abstract class StageDlcLibs : DefaultTask() {

    /** The source-set directory build_opencv_android.sh writes to. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jniDir: ConfigurableFileCollection

    /** The libraries to publish, in the order NativeLibs loads them. */
    @get:Input
    abstract val libs: ListProperty<String>

    /** Uploaded as release assets. */
    @get:OutputDirectory
    abstract val stagingDir: DirectoryProperty

    /** Packaged into the APK, so the digests are covered by its signature. */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val available = jniDir.files.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()
        }
        val staging = stagingDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val assets = assetsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val digests = StringBuilder()

        for (lib in libs.get()) {
            val source = available.firstOrNull { it.name == lib }
                ?: throw GradleException(
                    "No $lib in src/main/jniLibs — run scripts/build_opencv_android.sh before " +
                        "publishing DLC assets."
                )
            source.copyTo(File(staging, lib), overwrite = true)

            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            digests.append(hex).append("  ").append(lib).append('\n')
            logger.lifecycle("staged $lib (${source.length() / 1024}k, ${hex.take(12)}…)")
        }
        File(assets, "dlc-libs.sha256").writeText(digests.toString())
    }
}

val stageDlcLibs = tasks.register<StageDlcLibs>("collectDlcLibs") {
    group = "build"
    description = "Stage the full-mode DLC native libs and record their digests for the APK."
    jniDir.from(layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a"))
    libs.set(dlcLibs)
    stagingDir.set(layout.buildDirectory.dir("dlc-libs"))
    assetsDir.set(layout.buildDirectory.dir("generated/dlcDigests"))
}

// KeepRulesTest reads proguard-rules.pro, so a change there must invalidate the test task.
tasks.withType<Test>().configureEach {
    inputs.file("proguard-rules.pro").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:apppicker"))
    // The arithmetic that decides whether a page was read at all — the cascade's judge (see
    // ReadingCascade). Reading stays where it was; only the verdict is consulted here.
    implementation(project(":core:docread"))
    implementation(project(":core:ipc"))
    implementation(project(":core:logging"))
    implementation(project(":core:nativelibs"))
    implementation(project(":core:preferences"))
    implementation(project(":vendor:ppocr-sdk"))
    // ML-based document corner detector (see vendor/docquad-sdk/NOTICE) — brings in
    // ai.onnxruntime.* transitively; libonnxruntime.so itself is already excluded from packaging
    // above and loaded via NativeLibManager's DLC download instead, same as ppocr-sdk's own use of
    // this artifact.
    implementation(project(":vendor:docquad-sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Persisted "selected OCR zone" setting.
    implementation(libs.androidx.datastore.preferences)

    // Camera capture for the scan screen.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // DLC native library downloading
    implementation(libs.okhttp)

    testImplementation(libs.junit)

    // Instrumented tests — real on-device runs, needed for NativeLibManagerInstrumentedTest to
    // catch native-linking regressions (UnsatisfiedLinkError etc.) that a JVM-only unit test or a
    // plain compile can never observe (see docs/BUILD_TIME_DEPENDENCIES.md's onnxruntime-android
    // section).
    androidTestImplementation(libs.androidx.junit)
    // Carries androidx.test:runner, the AndroidJUnitRunner named in testInstrumentationRunner —
    // ext:junit alone does not, and without it the test APK crashes before any test runs.
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
