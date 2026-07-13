# :vendor:ppocr-sdk (native JNI bridge to PaddleOCR + OpenCV) and org.opencv aren't audited for R8 —
# JNI native-method classes are covered by AGP's default keep rule, but keep both packages wholesale
# for extra safety since native/JNI bridging code is the highest-risk category for silent R8 breaks.
-keep class com.paddle.ocr.** { *; }
-dontwarn com.paddle.ocr.**
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
