package com.voxapps.apppicker

/**
 * Every UI string [AppPickerCard] needs, supplied by the caller from its own app's LanguageManager
 * — each app has its own, incompatible LanguageManager type, so this module stays free of either
 * app's localization dependency rather than picking one.
 *
 * [defaultAppSummaryFormat]/[appsSelectedNoDefaultFormat]/[starredCountSummaryFormat] are
 * `String.format` templates:
 * - defaultAppSummaryFormat: one `%s` (the default app's name), one `%d` (count of other selected apps)
 * - appsSelectedNoDefaultFormat: one `%d` (count of selected apps) — used when the call site is in
 *   single-default mode ([AppPickerCard]'s `defaultPackage`/`onSetDefault`) and no default is set yet.
 * - starredCountSummaryFormat: two `%d` (count of selected apps, count of starred apps) — used when
 *   the call site is in independently-toggled star mode ([AppPickerCard]'s `starredPackages`/
 *   `onToggleStar`, e.g. "which of these payment apps are banks") instead of a single default.
 */
data class AppPickerStrings(
    val searchPlaceholder: String,
    val clear: String,
    val showAllApps: String,
    val showUserApps: String,
    val showSystemApps: String,
    val noAppsFound: String,
    val expand: String,
    val collapse: String,
    val noneLabel: String,
    val notSelected: String,
    val noAppsSelected: String,
    val defaultAppSummaryFormat: String,
    val appsSelectedNoDefaultFormat: String,
    val starredCountSummaryFormat: String,
    val selected: String,
    val setAsDefault: String,
    val removeDefault: String
)
