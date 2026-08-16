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

# Jetpack Glance (home-screen widget) renders content via a WorkManager background worker and
# resolves click actions (ActionCallback subclasses) by reflectively loading their class name from
# a RemoteViews PendingIntent extra — R8 can't see either path via static analysis. Without these
# keeps the worker silently fails to start ("WM-WorkerWrapper: Could not create Input Merger
# androidx.work.OverwritingInputMerger") and the widget never advances past its static placeholder.
-keep class androidx.work.** { *; }
-keep class androidx.glance.** { *; }
-keep class com.voxapps.expenses.ui.widget.** { *; }

# Parsed from JSON by Gson, so their field names must survive R8 — a stripped field is not a
# compile error and not a unit-test failure: the release build simply reads an empty schema and
# refuses it, which is exactly how this was found (on a device, from a log line).
-keep class com.voxapps.expenses.data.ExternalServicesSchema { *; }
-keep class com.voxapps.expenses.data.VocabulariesSchema { *; }
-keep class com.voxapps.expenses.data.ExternalService { *; }
-keep class com.voxapps.expenses.domain.llm.ReceiptTemplateSchema { *; }
-keep class com.voxapps.expenses.domain.llm.HeaderTemplateEntry { *; }
-keep class com.voxapps.expenses.domain.llm.ColumnTemplateEntry { *; }
-keep class com.voxapps.expenses.domain.llm.ItemTemplateEntry { *; }
-keep class com.voxapps.expenses.domain.llm.FooterTemplateEntry { *; }
