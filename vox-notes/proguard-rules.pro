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

# Jetpack Glance (home-screen widget) renders content via a WorkManager background worker and
# resolves click actions (ActionCallback subclasses) by reflectively loading their class name from
# a RemoteViews PendingIntent extra — R8 can't see either path via static analysis. Without these
# keeps the worker silently fails to start ("WM-WorkerWrapper: Could not create Input Merger
# androidx.work.OverwritingInputMerger") and the widget never advances past its static placeholder.
-keep class androidx.work.** { *; }
-keep class androidx.glance.** { *; }
-keep class com.voxapps.notes.ui.widget.** { *; }

# Parsed from JSON by Gson for settings backup and restore, so the field names must survive R8.
# A stripped field is not a compile error and not a unit-test failure — the release build simply
# reads an object with nothing in it, and a restore quietly produces defaults.
-keep class com.voxapps.notes.data.preferences.NotesSettings { *; }
