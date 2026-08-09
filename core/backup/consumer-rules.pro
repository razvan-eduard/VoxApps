# Every app's *Settings data class round-trips through VoxSettingsRoundTrip's Gson reflection
# (toJson/parseOrDefault) — Gson matches JSON keys against the actual JVM field name at runtime.
# Without this, R8's default minification renames these fields on every release build, so an old
# backup's JSON keys silently stop matching a newer build's renamed fields: Gson can't error on a
# mismatched key, it just leaves every field at its Kotlin default — a restore that looks like it
# worked but quietly reset every setting. Keeping the field names (not the whole class — shrinking/
# inlining elsewhere is unaffected) makes the JSON shape stable across every future build.
-keepclassmembers class com.voxapps.**.data.preferences.*Settings {
    <fields>;
}
