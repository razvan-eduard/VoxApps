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
-keep class com.microsoft.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.microsoft.onnxruntime.**
-dontwarn com.k2fsa.sherpa.onnx.**

# Vosk
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# NewPipe Extractor
-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**

# General reflection/obfuscation safety for models
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
