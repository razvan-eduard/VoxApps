# General Android
-dontwarn android.util.**
-dontwarn android.net.**
-dontwarn android.app.**

# Spotify App Remote SDK
-keep class com.spotify.** { *; }
-dontwarn com.spotify.**
-dontwarn com.fasterxml.jackson.databind.**

# MediaPipe / Google GenAI
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.auto.value.**

# Rhino / Mozilla (Scripting)
-dontwarn java.beans.**
-dontwarn javax.script.**

# Keep everything for reflective access to Vox contract components
-keep class com.voxapps.ipc.** { *; }
-keep interface com.voxapps.ipc.** { *; }

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

# Vosk
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# NewPipe Extractor
-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**

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
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Plus explicit keeps for every class actually passed to gson.fromJson(...)/gson.toJson(...) in this
# app — @SerializedName-only protection isn't enough, these need to survive intact (fields AND a
# usable constructor), not just have their field names preserved.
-keep class com.voxapps.commander.data.preferences.AppSettings { *; }
-keep class com.voxapps.commander.data.preferences.AppAliasRule { *; }
-keep class com.voxapps.commander.data.remote.RemoteModelSchema { *; }
-keep class com.voxapps.commander.data.remote.RemoteEngineConfig { *; }
-keep class com.voxapps.commander.data.remote.RemoteModelItem { *; }
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
