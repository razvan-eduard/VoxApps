package com.voxapps.commander.data.remote

import com.voxapps.commander.BuildConfig
import com.voxapps.nativelibs.NativeLibs

/**
 * Commander's always-needed native payload — everything except whisper and the local LLM.
 *
 * None of these is optional at runtime: onnxruntime backs OpenWakeWord, vosk backs the Vosk
 * engines and sherpa Piper's synthesis, so anyone using a wake word fetches at least one on first
 * launch. In `minimal` they ship inside the APK and nothing downloads; `full` strips them out for
 * the 30MB IzzyOnDroid limit they were built for.
 *
 * Whisper is not here and never was: it is the one genuinely optional payload (~107MB, only if
 * you pick Whisper STT, with the Vulkan backend linked in and probed at first run), excluded by
 * AGP in both modes and fetched on demand. The llama.cpp runtime follows the same shape
 * (LlamaEngineManager, per-commit release, fetched when a local LLM is actually selected).
 *
 * Order matters: libsherpa-onnx-jni.so's only external NEEDED entry is libonnxruntime.so
 * (confirmed via readelf), so onnxruntime loads first. libvosk.so is self-contained — only system
 * libs — so its relative position does not matter.
 */
object NativeLibManager : NativeLibs(
    tagPrefix = "commander",
    versionName = BuildConfig.VERSION_NAME,
    libs = listOf(
        "libonnxruntime.so",
        "libvosk.so",
        "libsherpa-onnx-jni.so"
    ),
    bundled = BuildConfig.DLC_MODE == "minimal"
)
