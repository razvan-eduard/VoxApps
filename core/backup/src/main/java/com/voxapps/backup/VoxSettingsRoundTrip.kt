package com.voxapps.backup

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * One shared Gson instance and round-trip pair, instead of a private `val gson = Gson()` per app's
 * export/import handler. [parseOrDefault]'s [coalesce] parameter is REQUIRED (not hidden magic): a
 * JSON key that's explicitly present but `null` always bypasses Kotlin's non-null guarantee via
 * Gson's reflective construction — an old export whose field was nullable/absent-by-convention at
 * the time it was written reproduces exactly this shape. (A merely *missing* key sometimes gets
 * backfilled by Kotlin's own default-value constructor instead, but that's Gson-version- and
 * class-size-dependent — this codebase has already hit real crashes from large settings classes
 * where it silently didn't apply, e.g. `searchProviderApiKeys`/`paymentSourcePackages` — so don't
 * rely on it either way.) Keeping [coalesce] an explicit parameter, rather than trying to infer it,
 * keeps that per-field list visible at each call site instead of buried at the bottom of an
 * unrelated handler file.
 */
object VoxSettingsRoundTrip {
    private val gson = Gson()

    fun toJson(settings: Any): String = gson.toJson(settings)

    /** Returns `null` on malformed JSON — the caller must skip restoring rather than apply bare
     *  defaults (Commander's existing contract: a corrupt/foreign import touches nothing). [coalesce]
     *  defaults to identity for settings classes with no at-risk fields (all-primitive, or already
     *  fully nullable). */
    fun <T : Any> parseOrNull(
        json: String,
        clazz: Class<T>,
        coalesce: (T) -> T = { it }
    ): T? = try {
        gson.fromJson(json, clazz)?.let(coalesce)
    } catch (e: JsonSyntaxException) {
        null
    }

    /** Returns [default] on malformed JSON instead of `null` (Expenses'/Notes'/Calendar's existing
     *  contract — used only where the call site already restores that default rather than skipping). */
    fun <T : Any> parseOrDefault(
        json: String,
        clazz: Class<T>,
        default: T,
        coalesce: (T) -> T = { it }
    ): T = parseOrNull(json, clazz, coalesce) ?: default
}
