# Hub has no Room/SQLCipher/reflection-heavy dependencies — default AGP + AndroidX consumer rules
# (DataStore, Compose, kotlinx.coroutines) are sufficient beyond the Tink rules below.

# androidx.security.crypto (EncryptedSharedPreferences, used by domain/sync/SyncPeerStore) pulls in
# Google Tink, which references a handful of compile-time-only annotation classes (errorprone,
# javax.annotation) that aren't on the runtime classpath — safe to silence per R8's own
# missing_rules.txt suggestion; Tink's actual crypto code doesn't need these present at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
