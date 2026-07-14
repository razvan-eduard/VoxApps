# Room entities/DAOs are covered by Room's own bundled consumer-rules.pro.
# SQLCipher's JNI native-method classes are covered by AGP's default keepclasseswithmembernames
# rule, but keep the package explicitly too — its cursor-factory/driver loading has a history of
# reflection-related R8 issues in other projects.
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# androidx.security.crypto bundles Google Tink, which references errorprone/javax.annotation
# annotation classes that are compile-time-only and genuinely absent at runtime — safe to ignore,
# this is Tink's well-known standard R8 warning, not a real missing dependency.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# IPC & Service Entry Points
# Prevent R8 from renaming or stripping receivers and services needed for cross-app IPC.
-keep class com.voxapps.**.receiver.** { *; }
-keep class com.voxapps.**.service.** { *; }
