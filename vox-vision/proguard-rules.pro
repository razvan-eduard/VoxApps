# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# PaddleOCR / PPOCR
-keep class com.paddle.ocr.** { *; }
-dontwarn com.paddle.ocr.**

# General reflection/obfuscation safety for models
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
