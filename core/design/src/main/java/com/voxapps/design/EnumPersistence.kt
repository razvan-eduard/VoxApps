package com.voxapps.design

/**
 * Decoding for enums that are persisted by `name` — settings values, DataStore/Room columns, and
 * IPC/JSON payloads, all of which store an enum as a plain [String] and must survive reading back a
 * name that no longer exists (a constant renamed or removed between versions, or a value written by
 * a newer build of a sibling app).
 *
 * [Enum.valueOf] throws on an unknown name, so every such read had to be spelled
 * `runCatching { SomeEnum.valueOf(raw) }.getOrDefault(SomeEnum.FALLBACK)`. That form repeats the
 * enum's name twice and buries the actual intent — pick this value, or this default — inside
 * exception plumbing. `reified` [E] lets the type come from the expected result instead, so the
 * call reads as the substitution it is.
 *
 * Lives here rather than in a general utility module because most persisted enums in the apps are
 * this module's own ([VoxDarkMode], [com.voxapps.design.effects.TodayEffect],
 * [com.voxapps.design.effects.TodayEffectStyle]) and every app already depends on `:core:design`.
 */
inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
    if (isNullOrBlank()) default else runCatching { enumValueOf<E>(this) }.getOrDefault(default)

/**
 * As [toEnumOr], for the callers that treat an unrecognized name as "absent" and supply their own
 * behaviour for it rather than substituting a fixed constant.
 */
inline fun <reified E : Enum<E>> String?.toEnumOrNull(): E? =
    if (isNullOrBlank()) null else runCatching { enumValueOf<E>(this) }.getOrNull()
