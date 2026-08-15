# General Android
-dontwarn android.util.**
-dontwarn android.net.**
-dontwarn android.app.**

# Spotify App Remote SDK
-keep class com.spotify.** { *; }
-dontwarn com.spotify.**
-dontwarn com.fasterxml.jackson.databind.**

# Rhino / Mozilla (Scripting)
-dontwarn java.beans.**
-dontwarn javax.script.**

# Keep everything for reflective access to Vox contract components
-keep class com.voxapps.ipc.** { *; }
-keep interface com.voxapps.ipc.** { *; }

# Whisper.cpp JNI bridge: native-lib.cpp's JNIEXPORT functions are named
# Java_com_whispercpp_whisper_WhisperLib_00024Companion_* — JNI's automatic native-method linking
# matches by this exact literal class name, so if R8 renames WhisperLib (completely unprotected
# before this rule), every native call (initContext/fullTranscribe/getTextSegment/...) would fail
# with UnsatisfiedLinkError at runtime. Same class of bug as the ai.onnxruntime fix below, just not
# yet triggered/reported since it only surfaces when actually transcribing audio.
-keep class com.whispercpp.whisper.WhisperLib { *; }
-keep class com.whispercpp.whisper.WhisperLib$Companion { *; }

# llama.cpp JNI bridge: llama_jni.cpp's JNIEXPORT functions are named
# Java_com_voxapps_llamacpp_LlamaBridgeImpl_* — the same by-literal-name linking as WhisperLib
# above, so a renamed LlamaBridgeImpl fails with UnsatisfiedLinkError at first local-LLM use.
-keep class com.voxapps.llamacpp.** { *; }

# LiteRT-LM: the SDK's own NativeLibraryLoader resolves liblitertlm_jni.so by literal name, and its
# JNI counterpart looks its Kotlin types up the same way — the same by-name linking as the bridges
# above, with the same UnsatisfiedLinkError at first use if R8 renames them.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Sherpa-ONNX / ONNX Runtime
# The onnxruntime-android AAR's actual Java package is ai.onnxruntime.* (com.microsoft.onnxruntime
# is just the Maven groupId, not a real package here) -- this rule targeted the wrong name entirely,
# so R8 was freely renaming ai.onnxruntime.* classes while their native JNI counterpart
# (libonnxruntime4j_jni.so) does FindClass()/GetMethodID() lookups by hardcoded original class name,
# aborting with "JNI DETECTED ERROR IN APPLICATION: java_class == null" (confirmed on-device, native
# tombstone crash starting WakeWordService with the OpenWakeWord engine, which runs ONNX inference).
-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn com.k2fsa.sherpa.onnx.**

# Vosk (and its transitive JNA dependency — org.vosk's own native bridge is built on top of
# com.sun.jna, not a direct JNI bridge. JNA's Native.initIDs() native method looks up
# com.sun.jna.Pointer's "peer" field by exact class/field name at class-init time; with no keep rule
# for it, R8 was free to rename/strip that field, crashing with "UnsatisfiedLinkError: Can't obtain
# peer field ID for class com.sun.jna.Pointer" the moment org.vosk.Model was ever constructed —
# confirmed on-device starting the Vosk wake word service. Same class of bug as the ai.onnxruntime
# and Whisper JNI fixes above: a library whose only "usage" R8 can see statically is a plain field
# reference, but whose actual consumer is native code doing a lookup by literal name.)
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# NewPipe Extractor
-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**

# Picovoice Porcupine / its android-voice-processor dependency — same class of bug as onnxruntime/
# Whisper/Vosk above (native libpv_porcupine.so bridge), had zero protection before this rule.
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

# IPC & Service Entry Points
# Prevent R8 from renaming or stripping receivers and services needed for cross-app IPC.
-keep class com.voxapps.**.receiver.** { *; }
-keep class com.voxapps.**.service.** { *; }

# OpenWakeWord (Numerical Stability)
# Completely disable optimizations for this package to prevent precision loss.
-keep class com.rementia.openwakeword.** { *; }
-dontwarn com.rementia.openwakeword.**
-dontoptimize

# Gson: the @SerializedName rule below only protects fields, not the class itself — R8's class
# merging/inlining optimizations can still turn a POJO that's *only* ever reached via
# gson.fromJson(json, X::class.java) reflection (invisible to R8's static analysis) into something
# Gson's ConstructorConstructor can't instantiate at all ("Abstract classes cannot be instantiated" /
# "Adjust R8 config or register an InstanceCreator" at runtime — confirmed on-device for
# search_definitions.json/models.json/intents.json/AppSettings alike, every JSON asset in the app).
# Official Gson-recommended baseline:
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
# Jetpack Glance (home-screen widget) renders content via a WorkManager background worker and
# resolves click actions (ActionCallback subclasses) by reflectively loading their class name from
# a RemoteViews PendingIntent extra — R8 can't see either path via static analysis. Without these
# keeps the worker silently fails to start ("WM-WorkerWrapper: Could not create Input Merger
# androidx.work.OverwritingInputMerger") and the widget never advances past its static placeholder.
-keep class androidx.work.** { *; }
-keep class androidx.glance.** { *; }
-keep class com.voxapps.commander.ui.widget.** { *; }

-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Plus explicit keeps for every class actually passed to gson.fromJson(...)/gson.toJson(...) in this
# app — @SerializedName-only protection isn't enough, these need to survive intact (fields AND a
# usable constructor), not just have their field names preserved.
#
# This list must grow with every new schema type, INCLUDING types only reached as a nested field of
# one already listed here: R8 judges each class on its own, and a class Gson alone instantiates has
# no visible constructor call, so it gets abstracted away. Adding EntryPoint to models.json without
# this line made the bundled schema unparseable in release builds only — which then read as
# "asset version 0", losing the no-downgrade comparison and silently handing the app an older remote
# schema. Debug builds and unit tests cannot see any of it; only a release install can.
-keep class com.voxapps.commander.data.preferences.AppSettings { *; }
-keep class com.voxapps.commander.data.preferences.AppAliasRule { *; }
-keep class com.voxapps.commander.data.remote.RemoteModelSchema { *; }
-keep class com.voxapps.commander.data.remote.RemoteEngineConfig { *; }
-keep class com.voxapps.commander.data.remote.RemoteModelItem { *; }
-keep class com.voxapps.commander.data.remote.EntryPoint { *; }
# AuthDeclaration now ships in :core:services and is kept by that module's consumer rules.
-keep class com.voxapps.commander.data.remote.VirtualModelItem { *; }
-keep class com.voxapps.commander.domain.intent.model.NluIntent { *; }
-keep class com.voxapps.commander.domain.intent.registry.IntentCatalog$IntentsSchema { *; }
-keep class com.voxapps.commander.domain.intent.registry.IntentCatalog$TaxonomyDef { *; }
-keep class com.voxapps.commander.domain.intent.registry.IntentCatalog$IntentDef { *; }
-keep class com.voxapps.commander.domain.intent.handler.PipedSearchHelper$PipedSearchItem { *; }
-keep class com.voxapps.commander.domain.intent.registry.AppRegistry$AppEntry { *; }
-keep class com.voxapps.commander.domain.search.SearchDefinitionsSchema { *; }
-keep class com.voxapps.commander.domain.search.CategoryDefinition { *; }
-keep class com.voxapps.commander.domain.search.ProviderDefinition { *; }
-keep class com.voxapps.commander.domain.search.FieldMapping { *; }
-keep class com.voxapps.commander.domain.media.MediaServiceRegistry$MediaSchema { *; }
-keep class com.voxapps.commander.domain.media.MediaServiceRegistry$MediaBackend { *; }
-keep class com.voxapps.commander.domain.media.MediaServiceRegistry$MediaRegion { *; }
-keep class com.voxapps.commander.domain.intent.registry.ApiIntegrationsSchema { *; }
-keep class com.voxapps.commander.domain.intent.registry.ApiIntegration { *; }
-keep class com.voxapps.commander.domain.intent.registry.CapabilitySlot { *; }
-keep class com.voxapps.commander.domain.intent.registry.SequenceStep { *; }
-keep class com.voxapps.commander.domain.intent.registry.RetryDef { *; }
-keep class com.voxapps.commander.domain.intent.registry.PreferRule { *; }
