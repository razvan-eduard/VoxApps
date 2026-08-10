package com.voxapps.commander.data.remote

import com.voxapps.commander.BuildConfig
import com.voxapps.nativelibs.NativeLibs

/**
 * Commander's always-needed native payload — everything except whisper.
 *
 * None of these is optional at runtime: onnxruntime backs OpenWakeWord, vosk backs the Vosk engines,
 * litertlm the on-device LLM and sherpa Piper's synthesis, so anyone using a wake word fetches at
 * least one on first launch. In `minimal` they ship inside the APK and nothing downloads; `full`
 * strips them out for the 30MB IzzyOnDroid limit they were built for.
 *
 * Whisper is not here and never was: it is the one genuinely optional payload (~193MB, only if you
 * pick Whisper STT, with the Vulkan variant only where the GPU supports it), excluded by AGP in both
 * modes and fetched on demand.
 *
 * Order matters: libsherpa-onnx-jni.so's only external NEEDED entry is libonnxruntime.so (confirmed
 * via readelf), so onnxruntime loads first. liblitertlm_jni.so and libvosk.so are self-contained —
 * only system libs — so their relative position does not matter.
 */
object NativeLibManager : NativeLibs(
    tagPrefix = "commander",
    versionName = BuildConfig.VERSION_NAME,
    libs = listOf(
        "libonnxruntime.so",
        "liblitertlm_jni.so",
        "libvosk.so",
        "libsherpa-onnx-jni.so"
    ),
    bundled = BuildConfig.DLC_MODE == "minimal"
)
