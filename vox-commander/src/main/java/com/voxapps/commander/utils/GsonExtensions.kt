package com.voxapps.commander.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Decodes [json] into [T], yielding `null` for absent, blank, or unparseable input instead of
 * throwing.
 *
 * Gson erases generics, so decoding anything with a type argument normally needs a `Type` built by
 * hand — and for nested generics that means nesting the builder too, e.g. a
 * `Map<String, List<String>>` previously required
 * `TypeToken.getParameterized(Map::class.java, String::class.java, TypeToken.getParameterized(List::class.java, String::class.java).type).type`.
 * That expression is easy to get subtly wrong (swap two arguments and it still compiles, then fails
 * at runtime), and it is not checked against the variable it is assigned to. Because `reified` [T]
 * is substituted at the call site, `object : TypeToken<T>() {}` below expands to the concrete,
 * fully-nested token, so the compiler derives the type from the call's own type argument and the
 * hand-built form is not needed at all.
 *
 * Callers that want to record the failure pass [onError]; it receives the exception and is skipped
 * for merely absent input, since "no value stored yet" is normal rather than a parse failure.
 * Blank/absent input and a parse failure both yield `null`, so a caller wanting a fallback writes
 * `?: emptyList()` — matching what these call sites already did.
 */
inline fun <reified T> Gson.fromJsonOrNull(json: String?, onError: (Exception) -> Unit = {}): T? {
    if (json.isNullOrBlank()) return null
    return try {
        fromJson(json, object : TypeToken<T>() {}.type)
    } catch (e: Exception) {
        onError(e)
        null
    }
}
