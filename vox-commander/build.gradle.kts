import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.cyclonedx)
}

/*
 * How much of the native payload leaves the APK — see `voxDlc` in gradle.properties.
 *
 * One source for two decisions that must agree: whether the release strips these libs out, and
 * whether the app downloads them at first launch. Split across a build script and a Kotlin
 * constant, they drift into an APK missing libs nothing fetches, or an APK carrying libs it
 * downloads again anyway.
 *
 * Whisper is unaffected. It is excluded in both modes because it is the one payload that is
 * genuinely optional: ~107MB that a Vosk or cloud user never needs.
 */
val dlcMode = (project.findProperty("voxDlc") as String?) ?: "minimal"
require(dlcMode in setOf("minimal", "full")) { "voxDlc must be 'minimal' or 'full', got '$dlcMode'" }

/**
 * Built into src/main/jniLibs; fetched on demand by WhisperEngineManager.
 *
 * Two libraries, not six. The CMake build links ggml statically (BUILD_SHARED_LIBS OFF in
 * src/main/cpp/CMakeLists.txt), so libwhisper.so defines every ggml symbol it uses and declares no
 * DT_NEEDED on a ggml library — there is no separate libggml*.so for it to bind to. libomp.so is
 * its one real shared dependency.
 */
val whisperLibs = listOf(
    "libwhisper.so",
    "libomp.so"
)

/**
 * Inside the APK in `minimal`; published as release assets and fetched at first launch in `full`.
 * Must stay in step with NativeLibManager.libs, which is the list the app tries to download.
 */
val dlcLibs = listOf(
    "libonnxruntime.so",
    "libvosk.so",
    "libsherpa-onnx-jni.so"
)

/**
 * Built into src/main/jniLibs by scripts/check_llama.sh; fetched on demand by LlamaEngineManager.
 * One library: ggml is linked statically (BUILD_SHARED_LIBS OFF in src/main/cpp/llama-build) and
 * OpenMP is compiled out, so libllama.so has no non-platform DT_NEEDED at all.
 * Must stay in step with LlamaEngineManager.LLAMA_LIBS, publish_llama_libs.sh and
 * check_llama_published.sh.
 */
val llamaLibs = listOf(
    "libllama.so"
)

/**
 * sherpa-onnx ships three native entry points; only libsherpa-onnx-jni.so is ever loaded (its Java
 * bindings load "sherpa-onnx-jni" by name, and its only external NEEDED lib is libonnxruntime.so).
 * The other two are dead weight — dropped from every release build, never published, never
 * downloaded.
 */
val unusedSherpaLibs = listOf("libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so")

/**
 * Two artifacts put a libonnxruntime.so at this path: sherpa-onnx's own AAR (~21MB, built by the
 * sherpa project) and onnxruntime-android (~28MB, Microsoft's). They are different binaries, not
 * two copies of one. The sherpa build is the one that must win — libsherpa-onnx-jni.so needs the
 * symbol version it exports (VERS_1.27.0, see core/wakeword/build.gradle.kts), and it is the copy
 * every published DLC asset so far has been.
 */
val onnxRuntimePath = "lib/arm64-v8a/libonnxruntime.so"
val onnxRuntimeArtifact = "sherpa-onnx"


android {
    namespace = "com.voxapps.commander"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.voxapps.commander"
        minSdk = 29
        targetSdk = 36
        versionCode = 26
        versionName = "0.25-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    // CI-only release signing: RELEASE_KEYSTORE_PATH is only set in release-commander.yml (decoded
    // from a GitHub Actions secret there), so local `./gradlew assembleRelease` without it still
    // produces an unsigned APK exactly as before.
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
                // Stated rather than defaulted. Signing moved from a post-build apksigner call to
                // Gradle, and AGP's default here is v2 alone — while every published release so far
                // is v3 (verified against the commander-v0.16-beta asset). An installed app updates
                // only from an APK signed by the same certificate, so the scheme is not a detail to
                // let a default change. v1 is JAR signing, unnecessary above API 24; minSdk is 29.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // Native packaging is NOT configured here — see the androidComponents block below the
        // android {} block for why a `packaging {}` inside a build type does not do what it reads
        // like.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    lint {
        disable += "UnprotectedBroadcastReceiver"
    }
    buildFeatures {
        compose = true
        // NativeLibManager builds its DLC download URL from BuildConfig.VERSION_NAME — a
        // compile-time constant that can't disagree with the running build (see its doc comment).
        buildConfig = true
    }

    defaultConfig {
        // Read by NativeLibManager, so the runtime cannot disagree with how the APK was packaged.
        buildConfigField("String", "DLC_MODE", "\"$dlcMode\"")
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += "META-INF/jpms.args"
        }
        jniLibs {
            // Forces native libraries to be extracted to nativeLibraryDir as real files on install.
            // Load-bearing: WhisperEngineManager.isWhisperAvailable() and AppStateManager's
            // diagnostics probe File(nativeLibraryDir, name) — under the default (false) the
            // libraries are mapped straight out of the APK and never exist as files there, so both
            // probes report missing for libraries that are present and loadable.
            useLegacyPackaging = true
            // This app ships arm64-v8a only (see abiFilters). mergeNativeLibs still processes every
            // other ABI, where onnxruntime-android and sherpa-onnx collide on the same path and fail
            // the build outright — for libraries that would then be dropped anyway. Excluding them
            // here removes the collision and the wasted work, and is variant-independent, so this
            // one belongs at the android level rather than in the per-variant block below.
            excludes += setOf(
                "lib/armeabi-v7a/**",
                "lib/x86/**",
                "lib/x86_64/**"
            )
        }
    }
    androidResources {
        noCompress += ".onnx"
    }
}

/*
 * Which native libraries each variant packages.
 *
 * This is here, and not in the `buildTypes` blocks, because a `packaging {}` written inside a build
 * type is not scoped to that build type. AGP 9.6.1 applies it to every variant: an exclude added to
 * the release block alone also removes the file from mergeDebugNativeLibs' output (measured with a
 * marker library). Only the variant API below scopes for real.
 *
 * That is what made AGP's excludes look broken. The debug block carried a pickFirst for
 * libonnxruntime.so — needed there, because two artifacts provide that path — and it reached the
 * release variant, where a pickFirst for a path silently beats an exclude for the same path. So the
 * release excludes for the DLC libs did nothing, the conclusion drawn was that AGP could not be
 * trusted to exclude native libs at all, and the libs were instead stripped out of the built APK
 * zip afterwards by a shell script that then had to re-sign it. Scoped properly, the excludes work
 * on the first attempt: release drops all ten libraries, debug keeps every one.
 *
 * The remaining cost of the old approach was the bundle: a zip edit cannot reach an AAB, so the
 * published .aab carried ~37MB of libraries the APK did not.
 */
androidComponents {
    onVariants { variant ->
        val jniLibs = variant.packaging.jniLibs
        if (variant.buildType == "release") {
            jniLibs.excludes.addAll(whisperLibs.map { "lib/arm64-v8a/$it" })
            // Same treatment as whisper: built locally, published per-commit, fetched on demand.
            jniLibs.excludes.addAll(llamaLibs.map { "lib/arm64-v8a/$it" })
            // Never loaded in any mode (see unusedSherpaLibs), so every release drops them.
            jniLibs.excludes.addAll(unusedSherpaLibs.map { "lib/arm64-v8a/$it" })
            if (dlcMode == "full") {
                // Excluding a path drops every artifact's copy of it, so the two libonnxruntime.so
                // sources cannot collide here and no pickFirst is needed.
                jniLibs.excludes.addAll(dlcLibs.map { "lib/arm64-v8a/$it" })
            } else {
                jniLibs.pickFirsts.add(onnxRuntimePath)
            }
        } else {
            // Debug keeps everything, so the two sources still collide and still need resolving.
            jniLibs.pickFirsts.add(onnxRuntimePath)
        }

        // In `full` the digests of the libraries this build will download go into the APK, where the
        // APK's own signature covers them. That is what makes them worth checking: a digest fetched
        // from the same place as the library proves nothing, because whoever can serve one can serve
        // the other. Wiring them as a generated asset also makes packaging depend on the staging
        // task, so `assembleRelease` produces both the APK and the files to upload beside it.
        if (dlcMode == "full" && variant.buildType == "release") {
            variant.sources.assets?.addGeneratedSourceDirectory(stageDlcLibs, StageDlcLibs::assetsDir)
        }

        // Unconditional for release, unlike the DLC digests above: the Whisper libraries are excluded
        // from every release build in both modes, so every release downloads them and every release
        // needs something to check them against.
        //
        // Debug gets them too whenever the libraries are on disk to hash — without the assets a
        // sideloaded debug APK falls back to the floating `whisper-libs` tag and unverified
        // downloads. Gated on presence because a checkout without the compiled libraries (CI's
        // -PvoxSkipNativePrep builds) must still assemble; those APKs record nothing, exactly as
        // before.
        val whisperLibsOnDisk = whisperLibs.all {
            File(projectDir, "src/main/jniLibs/arm64-v8a/$it").exists()
        }
        if (variant.buildType == "release" || whisperLibsOnDisk) {
            variant.sources.assets?.addGeneratedSourceDirectory(hashWhisperLibs, HashEngineLibs::assetsDir)
        }

        // Same rule for llama, for the same reasons: excluded from every release build, so every
        // release needs digests to verify its downloads against; debug records them whenever the
        // compiled library is on disk to hash.
        val llamaLibsOnDisk = llamaLibs.all {
            File(projectDir, "src/main/jniLibs/arm64-v8a/$it").exists()
        }
        if (variant.buildType == "release" || llamaLibsOnDisk) {
            variant.sources.assets?.addGeneratedSourceDirectory(hashLlamaLibs, HashEngineLibs::assetsDir)
        }
    }
}

/*
 * Stages the `full`-mode DLC libraries for upload as release assets.
 *
 * In `full` these libraries never reach merged_native_libs — they are excluded before it — so they
 * have to be taken from the resolved dependencies instead. This reads the same artifacts AGP itself
 * packages from, which is why it cannot drift from what the app expects to download.
 *
 * Wired to run after `assembleRelease` in `full` mode, so a local release and CI stage the same
 * files from the same place.
 */
/**
 * Stages the `full`-mode DLC libraries and records what they hash to.
 *
 * Two outputs, from one selection, on purpose. The staged `.so` files are uploaded as release
 * assets; the digests are written into the APK's assets so the running app knows what bytes to
 * expect back when it downloads them. Computing them separately would let the published library and
 * the recorded digest come from different files, which is worse than recording nothing.
 *
 * In `full` these libraries never reach merged_native_libs — they are excluded before it — so both
 * come from the resolved dependencies, the same artifacts AGP itself packages from.
 */
abstract class StageDlcLibs : DefaultTask() {

    /** The `android-jni` artifact view of the release runtime classpath. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jniArtifacts: ConfigurableFileCollection

    /** The libraries to publish, in the order NativeLibs loads them. */
    @get:Input
    abstract val libs: ListProperty<String>

    /**
     * lib name → a fragment of the artifact it must come from. Two artifacts can provide the same
     * file name and they are not interchangeable; the choice is never left to directory order.
     */
    @get:Input
    abstract val preferredArtifact: MapProperty<String, String>

    /** Uploaded as release assets. */
    @get:OutputDirectory
    abstract val stagingDir: DirectoryProperty

    /** Packaged into the APK, so the digests are covered by its signature. */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val available = jniArtifacts.files.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.path.contains("arm64-v8a") }.toList()
        }
        val staging = stagingDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val assets = assetsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val digests = StringBuilder()

        for (lib in libs.get()) {
            var candidates = available.filter { it.name == lib }
            preferredArtifact.get()[lib]?.let { marker ->
                candidates = candidates.filter { it.path.contains(marker) }
            }
            val source = when {
                candidates.isEmpty() ->
                    throw GradleException("No $lib among the resolved dependencies — cannot publish it as a DLC asset.")
                candidates.size > 1 ->
                    throw GradleException(
                        "$lib is provided by ${candidates.size} artifacts and no rule says which to publish:\n" +
                            candidates.joinToString("\n") { "  $it" }
                    )
                else -> candidates.single()
            }
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

            // Names the artifact, not the directory: which dependency a lib came from is the fact
            // worth having in the log when two of them provide the same file name.
            val artifact = source.parentFile?.parentFile?.parentFile?.name ?: "?"
            logger.lifecycle("staged $lib (${source.length() / 1024}k, ${hex.take(12)}…) from $artifact")
        }
        File(assets, "dlc-libs.sha256").writeText(digests.toString())
    }
}

val stageDlcLibs = tasks.register<StageDlcLibs>("collectDlcLibs") {
    group = "build"
    description = "Stage the full-mode DLC native libs and record their digests for the APK."
    // Resolved lazily; `isLenient` because this view only asks for the JNI artifacts and should not
    // fail on dependencies that have none.
    jniArtifacts.from(
        configurations.getByName("releaseRuntimeClasspath").incoming.artifactView {
            isLenient = true
            attributes { attribute(Attribute.of("artifactType", String::class.java), "android-jni") }
        }.files
    )
    libs.set(dlcLibs)
    preferredArtifact.set(mapOf("libonnxruntime.so" to onnxRuntimeArtifact))
    stagingDir.set(layout.buildDirectory.dir("dlc-libs"))
    assetsDir.set(layout.buildDirectory.dir("generated/dlcDigests"))
}

/*
 * Records what the Whisper libraries should hash to, for the app to check what it downloads.
 *
 * Same reasoning as the DLC digests above: the file lands in the APK, so the APK's signature covers
 * it, and a digest served from the release the library came from would prove nothing.
 *
 * The inputs are the libraries this build produced — the same files publish_whisper_libs.sh uploads
 * — so the recorded digest describes the binary that build expects, not whatever the release happens
 * to hold later.
 */
abstract class HashEngineLibs : DefaultTask() {

    @get:javax.inject.Inject
    abstract val execOps: org.gradle.process.ExecOperations

    @get:InputFiles
    abstract val libFiles: ConfigurableFileCollection

    @get:Input
    abstract val libs: ListProperty<String>

    /**
     * The whisper.cpp commit these libraries were built from.
     *
     * Written into the APK because it is what names the release the app downloads from. A single
     * reused tag would mean the app asking for "whatever is published now" — an address with no
     * version in it, which is how an install ends up running a different build from the one its APK
     * was compiled against.
     */
    @get:Input
    abstract val engineCommit: Property<String>

    /** Release tag prefix ("whisper-libs" / "llama-libs"); the tag asked is "<prefix>-<sha12>". */
    @get:Input
    abstract val tagPrefix: Property<String>

    /** Asset file names this task writes ("whisper-libs.sha256" / "whisper-libs.commit", etc). */
    @get:Input
    abstract val digestAssetName: Property<String>

    @get:Input
    abstract val commitAssetName: Property<String>

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    /**
     * The digests GitHub records for the published release's assets, or null when the release does
     * not exist or cannot be asked.
     *
     * The published release is what every install downloads, and it is not this machine's compile:
     * whisper.cpp does not build reproducibly across toolchains, so hashing the locally built
     * libraries writes digests that describe a binary nobody will ever be served. The install then
     * verifies a correct download against them, fails, and Whisper cannot be enabled — an APK that
     * builds, passes every gate that doesn't look, and breaks only on a user's phone.
     */
    private fun publishedDigests(tag: String): Map<String, String>? {
        val out = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(
                "gh", "release", "view", tag,
                "--json", "assets",
                "--jq", ".assets[] | \"\\(.digest)  \\(.name)\""
            )
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) return null
        return out.toString().lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 2 && parts[0].startsWith("sha256:"))
                    parts[1] to parts[0].removePrefix("sha256:")
                else null
            }.toMap().ifEmpty { null }
    }

    @TaskAction
    fun record() {
        val assets = assetsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val available = libFiles.files.filter { it.isFile }
        val digests = StringBuilder()

        val commit = engineCommit.get().trim()
        require(commit.length >= 12) { "Cannot resolve the ${tagPrefix.get()} source commit; got '$commit'." }
        File(assets, commitAssetName.get()).writeText(commit + "\n")

        val published = publishedDigests("${tagPrefix.get()}-${commit.take(12)}")

        for (lib in libs.get()) {
            val fromRelease = published?.get(lib)
            val hex: String
            if (fromRelease != null) {
                hex = fromRelease.lowercase()
                logger.lifecycle("${tagPrefix.get()} digest ${hex.take(12)}… $lib (published release)")
            } else {
                // No published release for this pin — a checkout mid-bump, or a machine without gh.
                // The local compile is all there is to describe; an install of THIS build that
                // downloads the (differently built) published set would fail verification, which is
                // why the release workflow's whisper-published gate refuses to publish in that state.
                val source = available.firstOrNull { it.name == lib }
                    ?: throw GradleException(
                        "$lib is missing from src/main/jniLibs and ${tagPrefix.get()}-${commit.take(12)} " +
                            "is not published — nothing exists to record a digest of."
                    )
                val digest = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                hex = digest.digest().joinToString("") { "%02x".format(it) }
                logger.lifecycle(
                    "${tagPrefix.get()} digest ${hex.take(12)}… $lib (LOCAL build — no published release for this pin)"
                )
            }
            digests.append(hex).append("  ").append(lib).append('\n')
        }
        File(assets, digestAssetName.get()).writeText(digests.toString())
    }
}

val hashWhisperLibs = tasks.register<HashEngineLibs>("recordWhisperDigests") {
    group = "build"
    description = "Record the published Whisper libraries' SHA-256 digests for the APK to verify downloads against."
    // The published digests are a network answer Gradle cannot track as an input, and a stale cached
    // set is exactly the defect this task exists to prevent. It costs two API calls; always run it.
    outputs.upToDateWhen { false }
    // By name: autoCompileWhisper is registered further down this file. The local libraries are the
    // fallback when no release exists for the pin, so they must exist before this runs.
    dependsOn("autoCompileWhisper")
    // The pin recorded in this commit, which is the same value publish_whisper_libs.sh names the
    // release after. providers.exec rather than a bare command so the configuration cache can track
    // it instead of being invalidated by it.
    engineCommit.set(
        providers.exec {
            commandLine(
                "git", "-C", rootDir.absolutePath,
                "rev-parse", "HEAD:vox-commander/src/main/cpp/whisper.cpp"
            )
        }.standardOutput.asText.map { it.trim() }
    )
    tagPrefix.set("whisper-libs")
    digestAssetName.set("whisper-libs.sha256")
    commitAssetName.set("whisper-libs.commit")
    // The files themselves, not the directory: a directory added to a file collection stays a
    // directory, and every entry would then be filtered out as "not a file".
    libFiles.from(whisperLibs.map { layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/$it") })
    libs.set(whisperLibs)
    assetsDir.set(layout.buildDirectory.dir("generated/whisperDigests"))
}

val hashLlamaLibs = tasks.register<HashEngineLibs>("recordLlamaDigests") {
    group = "build"
    description = "Record the published llama libraries' SHA-256 digests for the APK to verify downloads against."
    outputs.upToDateWhen { false }
    dependsOn("autoCompileLlama")
    // The index, not HEAD: `ls-files -s` answers with the staged gitlink, which equals HEAD's pin
    // on any committed checkout and still answers on the one checkout HEAD cannot serve — the
    // commit that introduces the submodule. Output shape: "160000 <sha> 0\t<path>".
    engineCommit.set(
        providers.exec {
            commandLine(
                "git", "-C", rootDir.absolutePath,
                "ls-files", "-s", "vox-commander/src/main/cpp/llama.cpp"
            )
        }.standardOutput.asText.map { it.trim().split(Regex("\\s+"))[1] }
    )
    tagPrefix.set("llama-libs")
    digestAssetName.set("llama-libs.sha256")
    commitAssetName.set("llama-libs.commit")
    libFiles.from(llamaLibs.map { layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/$it") })
    libs.set(llamaLibs)
    assetsDir.set(layout.buildDirectory.dir("generated/llamaDigests"))
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:apppicker"))
    implementation(project(":core:location"))
    implementation(project(":core:backup"))
    implementation(project(":core:ipc"))
    implementation(project(":core:identity"))
    implementation(project(":core:logging"))
    implementation(project(":core:nativelibs"))
    implementation(project(":core:services"))
    implementation(project(":core:preferences"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Security, Navigation, JSON
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.gson)

    // Generic LLM hook background work (survives OEM/Doze restrictions a plain Service doesn't)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.vosk.android)
    implementation(libs.jsoup)
    implementation(libs.androidx.media)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.reorderable)
    // Chrome Custom Tabs for Spotify dashboard setup
    implementation("androidx.browser:browser:1.10.0")
    // Spotify App Remote SDK (local AAR)
    implementation(files("libs/spotify-app-remote.aar"))
    // Porcupine Wake Word Engine (Picovoice)
    implementation("ai.picovoice:porcupine-android:4.0.2")
    // OpenWakeWord (fully open-source, ONNX-based wake word detection) — local fork with an RMS
    // silence gate patch (see core/wakeword/NOTICE); pristine upstream kept at
    // vendor/openwakeword-android-kt for sync (scripts/check_openwakeword_version.sh).
    // core:wakeword already declares onnxruntime-android directly (pinned there, independent of
    // vox-vision's own gradle/libs.versions.toml pin — see core/wakeword/build.gradle.kts for why
    // it must match sherpa-onnx's bundled copy instead) — vox-commander's own source never imports
    // ai.onnxruntime.* directly, so a second direct declaration here was a redundant duplicate
    // dependency, not a real requirement. Two sources contributing the same native libs is exactly
    // the kind of ambiguity that made libonnxruntime.so's arm64-v8a packaging/exclude behavior
    // unreliable (see release excludes above) — removing the duplicate leaves a single Maven-
    // resolved source (sherpa-onnx's AAR still separately bundles its own copy of the same path,
    // and currently wins the merge — see the pickFirst comment above).
    implementation(project(":core:wakeword"))
    implementation(project(":core:audio"))
    // Piper TTS via sherpa-onnx (on-device neural TTS)
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
    // Apache Commons Compress for .tar.bz2 extraction (Piper voice models)
    implementation("org.apache.commons:commons-compress:1.28.0")
    // NewPipe Extractor — YouTube search & video URL parsing (replaces Piped API dependency).
    // JitPack coordinate, same as Vosk — version pinned in gradle/libs.versions.toml, checked weekly
    // by scripts/check_newpipe_extractor_version.sh / .github/workflows/sync-newpipe-extractor.yml.
    implementation(libs.newpipe.extractor)
    // ProcessPhoenix — reliable app restart (handles process kill + relaunch)
    implementation("com.jakewharton:process-phoenix:3.0.0")
    // Home-screen widget (Jetpack Glance — current best practice over raw RemoteViews/AppWidgetProvider).
    // GlanceTheme itself lives in the base :glance artifact (a transitive dep of glance-appwidget),
    // already gets Material You dynamic color for free — no separate glance-material3 dependency needed.
    implementation(libs.androidx.glance.appwidget)
    // STT Engines (Whisper.cpp integration)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    // Reflection-based contract tests (GsonDtoContractTest, CommanderExportHandlerTest) use
    // kotlin.reflect.full; the retired Google SDKs used to carry kotlin-reflect transitively, so
    // this was only ever satisfied by accident. Declared where it is actually used.
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("androidx.test:core:1.7.0")
    // Real org.json for JVM unit tests — the android.jar stub throws "Stub!",
    // which blocks testing code that parses JSON via org.json (e.g. TextNormalizer, WakeWordProfile).
    testImplementation("org.json:json:20260719")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Runs the Whisper bash script.
val autoCompileWhisper = tasks.register<Exec>("autoCompileWhisper") {
    group = "build"
    description = "Check whisper.cpp upstream and rebuild through CMake when it is stale."
    
    commandLine("bash", "${project.rootDir}/scripts/vox", "native", "whisper")
}

// Runs the llama bash script.
val autoCompileLlama = tasks.register<Exec>("autoCompileLlama") {
    group = "build"
    description = "Check llama.cpp upstream and rebuild through CMake when it is stale."

    commandLine("bash", "${project.rootDir}/scripts/vox", "native", "llama")
}

// Checks the published Vosk version.
val autoCheckVosk = tasks.register<Exec>("autoCheckVosk") {
    group = "verification"
    description = "Check whether a newer Vosk has been published on JitPack."

    // bash, not sh: the script is a bash script (uses ==, [[ ]]) — same class of bug as the
    // build_opencv_android.sh fix (sh on Ubuntu runners is dash, which doesn't support these).
    commandLine("bash", "${project.rootDir}/scripts/vox", "check", "vosk")
}

// Checks the published NewPipeExtractor version.
val autoCheckNewPipeExtractor = tasks.register<Exec>("autoCheckNewPipeExtractor") {
    group = "verification"
    description = "Check whether a newer NewPipeExtractor has been published on JitPack."

    // bash, not sh: the script uses [[ ]] (same class of bug as the build_opencv_android.sh fix —
    // sh on Ubuntu runners is dash, which doesn't support bashisms like [[ ]]).
    commandLine("bash", "${project.rootDir}/scripts/vox", "check", "newpipe-extractor")
}

// Checks whether the local OpenWakeWord fork (core/wakeword) has fallen behind upstream's tags.
val autoCheckOpenWakeWord = tasks.register<Exec>("autoCheckOpenWakeWord") {
    group = "verification"
    description = "Check whether the OpenWakeWord submodule has fallen behind a newer upstream tag."

    // bash, not sh: the script uses a bash array (PATCHES=(...)) — same class of bug as the
    // build_opencv_android.sh fix (sh on Ubuntu runners is dash, which doesn't support this).
    commandLine("bash", "${project.rootDir}/scripts/vox", "check", "openwakeword")
}

// Every schema the family ships lives in one folder at the repo root, and the whole folder is
// copied into assets at build time. A list of file names used to live here, and adding a schema
// meant remembering to add it — the folder is the list now.
val copyShippedSchemas = tasks.register<Copy>("copyShippedSchemas") {
    group = "build"
    description = "Copies this app's schemas (and any shared ones) into src/main/assets/schemas/"
    from("${project.rootDir}/remote-schemas/commander") { include("*.json") }
    from("${project.rootDir}/remote-schemas/shared") { include("*.json") }
    // The signed manifest travels with the app so a *fresh* install has a rollback floor. Without
    // it lastSerial starts at zero and a first launch would accept any old, validly-signed manifest
    // — rollback protection would only ever protect installs that had already seen something newer.
    from("${project.rootDir}/remote-schemas") { include("manifest.json") }
    into("${projectDir}/src/main/assets/schemas")
}

// One command for "has anything upstream moved?", across every vendored and pinned dependency —
// not just Commander's three. Same scripts the sync workflows call.
//
//     ./gradlew :vox-commander:checkUpstream
//
// On demand only, for the reason spelled out below.
tasks.register<Exec>("checkUpstream") {
    group = "verification"
    description = "Ask every upstream (Vosk, NewPipe, OpenWakeWord, OpenCV, PaddleOCR, whisper) whether it has moved."
    commandLine("bash", "${project.rootDir}/scripts/vox", "check")
}

// The three autoCheck* tasks above are deliberately NOT wired into preBuild.
//
// "A newer Vosk exists" is a maintenance fact, not a build fact, and it already has a home: the
// weekly sync-*.yml workflows open a PR when an upstream actually moves. Delivering it a second
// time as a warning in every build bought nothing and cost three network round-trips per build,
// builds that behave differently offline, and — the one that mattered — a version check that
// overwrites vendored source files to dry-run a patch against upstream while the build is running.
// Nothing attached to a compile should be writing to the source tree.
//
// They remain runnable on demand, which is how a maintenance task should be reached:
//     ./gradlew :vox-commander:autoCheckVosk
//     ./scripts/check_openwakeword_version.sh
val skipNativePrep = providers.gradleProperty("voxSkipNativePrep").isPresent

// `assembleRelease` now produces exactly the APK that ships. It used to not: the DLC libs were
// stripped out of the built zip afterwards by a script that then re-signed it, so a locally built
// release APK bundled every library and the DLC download path could not be exercised on a device at
// all — which is how two bugs in it reached users. The packaging is done by AGP now (see the
// androidComponents block), in both modes, for the APK and the bundle alike, so there is nothing
// left to do afterwards and nothing left to drift.
//
//     ./gradlew :vox-commander:assembleRelease -PvoxDlc=full
//
// Set RELEASE_KEYSTORE_PATH and RELEASE_KEYSTORE_PASSWORD and Gradle signs it; leave them unset and
// it is left unsigned, exactly as before.

tasks.named("preBuild") {
    // Whisper stays: unlike the checks, it produces build output — the .so files this app links.
    // `-PvoxSkipNativePrep` drops it for a verification build that only needs to know whether the
    // Kotlin compiles, and would otherwise need the submodule, the NDK, shaderc and an SDK symlink
    // to reach the same answer.
    if (!skipNativePrep) {
        dependsOn(autoCompileWhisper)
        dependsOn(autoCompileLlama)
    }
    // Not optional anywhere: the shipped schemas are generated into assets, and the tests that
    // check code against them read the generated copy.
    dependsOn(copyShippedSchemas)
}

// A handful of ViewModel tests use viewModelScope.launch{} (not tied to the test's own TestScope),
// so a coroutine can still be in flight when that test's own @After tears down Dispatchers.Main —
// then resume later during a DIFFERENT test class sharing the same JVM and blow up there instead
// (surfaces as "UncaughtExceptionsBeforeTest" on an unrelated test). Forking a fresh JVM per test
// class eliminates this whole category of cross-class leakage without auditing every test file.
tasks.withType<Test> {
    forkEvery = 1
}

// Some tests read files the compiler never sees, so nothing else would make Gradle re-run them when
// those files change. The schemas are named at their source rather than in assets: assets/schemas is
// this build's own output, and a task cannot sensibly treat another task's output as its input.
tasks.withType<Test>().configureEach {
    inputs.file("proguard-rules.pro").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("${project.rootDir}/remote-schemas").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/assets/translations").withPathSensitivity(PathSensitivity.RELATIVE)
}
