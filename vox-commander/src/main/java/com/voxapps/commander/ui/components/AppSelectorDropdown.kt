package com.voxapps.commander.ui.components

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.apppicker.AppPickerCard
import com.voxapps.apppicker.AppPickerEntry
import com.voxapps.apppicker.AppPickerStrings
import com.voxapps.commander.domain.integration.VoxSatelliteRegistry
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.service.SpotifyRemoteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Builds the candidate app list shared by both [AppSelectorDropdown] overloads.
 *
 * Vox satellite apps (those implementing the :core:ipc contract) are driven over the IPC command bus,
 * not launched as normal intents — so they are hidden from the generic pickers ([excludeSatellites],
 * default true). The one exception is [satelliteDomain]: when set, the list is ONLY the satellites
 * advertising that domain (the "pick a preferred notes app" star row), overriding the generic list.
 */
@Composable
private fun rememberCandidateApps(
    domain: String?,
    extraPackages: List<String>,
    excludeSatellites: Boolean,
    satelliteDomain: String?
): List<AppRegistry.AppEntry> {
    val satellites by VoxSatelliteRegistry.apps.collectAsStateWithLifecycle()
    return remember(domain, extraPackages, excludeSatellites, satelliteDomain, satellites) {
        if (satelliteDomain != null) {
            // Satellite star row: only apps advertising this domain, resolved to AppEntry.
            return@remember VoxSatelliteRegistry.candidatesForDomain(satelliteDomain)
                .map { s ->
                    AppRegistry.resolveByPackage(s.packageName)
                        ?: AppRegistry.AppEntry(packageName = s.packageName, displayName = s.label)
                }
                .sortedBy { it.displayName.lowercase() }
        }
        val domainApps = if (domain != null) {
            AppRegistry.getInstalledAppsForDomain(domain)
        } else {
            AppRegistry.allInstalledApps()
        }
        val base = if (extraPackages.isEmpty()) {
            domainApps
        } else {
            val existingPkgs = domainApps.map { it.packageName }.toSet()
            val extraApps = AppRegistry.allInstalledApps().filter {
                it.packageName in extraPackages && it.packageName !in existingPkgs
            }
            (domainApps + extraApps).sortedBy { it.displayName.lowercase() }
        }
        if (excludeSatellites) {
            val satellitePkgs = satellites.map { it.packageName }.toSet()
            base.filter { it.packageName !in satellitePkgs }
        } else base
    }
}

/** Maps vox-commander's domain/intent-aware [AppRegistry.AppEntry] down to the shared module's
 *  minimal [AppPickerEntry] — the picker UI doesn't need domains/uriTemplates. */
private fun AppRegistry.AppEntry.toPickerEntry() = AppPickerEntry(packageName, displayName, isSystemApp)

/** Builds an [AppPickerStrings] from this app's own [com.voxapps.commander.domain.localization.LanguageManager],
 *  mirroring the `lm?.getString(key) ?: "English fallback"` pattern already used throughout this file. */
@Composable
private fun rememberAppPickerStrings(): AppPickerStrings {
    val lm = LocalLanguageManager.current
    return remember(lm) {
        AppPickerStrings(
            searchPlaceholder = lm?.getString("search_apps_placeholder") ?: "Search apps...",
            clear = lm?.getString("clear") ?: "Clear",
            showAllApps = lm?.getString("show_all_apps") ?: "Show all apps",
            showUserApps = lm?.getString("show_user_apps") ?: "Show user apps",
            showSystemApps = lm?.getString("show_system_apps") ?: "Show system apps",
            noAppsFound = lm?.getString("no_apps_found") ?: "No apps found",
            expand = lm?.getString("expand") ?: "Expand",
            collapse = lm?.getString("collapse") ?: "Collapse",
            noneLabel = lm?.getString("none_system_default") ?: "None (use system default)",
            notSelected = lm?.getString("not_selected") ?: "Not selected",
            noAppsSelected = lm?.getString("no_apps_selected") ?: "No apps selected",
            defaultAppSummaryFormat = lm?.getString("default_app_summary") ?: "Default: %s (+%d others)",
            appsSelectedNoDefaultFormat = lm?.getString("apps_selected_no_default") ?: "%d apps selected, no default",
            // Unused here — this screen's multi-select pickers are always single-default mode
            // (defaultPackage/onSetDefault), never independently-toggled star mode — kept for
            // AppPickerStrings' contract completeness.
            starredCountSummaryFormat = lm?.getString("apps_selected_starred_count") ?: "%d selected, %d starred",
            selected = lm?.getString("selected") ?: "Selected",
            setAsDefault = lm?.getString("set_as_default") ?: "Set as default",
            removeDefault = lm?.getString("remove_default") ?: "Remove default",
            done = lm?.getString("done_button") ?: "Done",
            cancel = lm?.getString("cancel_button") ?: "Cancel"
        )
    }
}

/**
 * Reusable app picker with inline expand (same pattern as DefaultApps domain cards).
 * Header shows selected app; tap to expand search + filter + scrollable app list.
 *
 * Single-select variant: pick one app (or none). Thin wrapper around the shared
 * [com.voxapps.apppicker.AppPickerCard] — this file only keeps what's specific to vox-commander:
 * satellite/domain-aware candidate filtering and Spotify OAuth interception.
 */
@Composable
fun AppSelectorDropdown(
    selectedPackage: String?,
    onAppSelected: (AppRegistry.AppEntry?) -> Unit,
    modifier: Modifier = Modifier,
    domain: String? = null,
    label: String = "Select app",
    allowNone: Boolean = true,
    extraPackages: List<String> = emptyList(),
    excludeSatellites: Boolean = true,
    satelliteDomain: String? = null,
    maxDropdownHeight: androidx.compose.ui.unit.Dp = 300.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSpotifyOAuthDialog by remember { mutableStateOf(false) }
    var spotifyOAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val allApps = rememberCandidateApps(domain, extraPackages, excludeSatellites, satelliteDomain)
    val strings = rememberAppPickerStrings()

    AppPickerCard(
        apps = allApps.map { it.toPickerEntry() },
        selectedPackage = selectedPackage,
        onAppSelected = { entry ->
            val app = entry?.let { e -> allApps.find { it.packageName == e.packageName } }
            if (app?.packageName == com.voxapps.commander.utils.PackageNames.SPOTIFY && !com.voxapps.commander.service.OAuth2Manager.isAuthorized("spotify")) {
                spotifyOAuthAction = { onAppSelected(app) }
                showSpotifyOAuthDialog = true
            } else {
                onAppSelected(app)
            }
        },
        strings = strings,
        modifier = modifier,
        label = label,
        allowNone = allowNone,
        maxDropdownHeight = maxDropdownHeight
    )

    SpotifyOAuthDialog(
        show = showSpotifyOAuthDialog,
        onDismiss = { showSpotifyOAuthDialog = false },
        onConnect = {
            showSpotifyOAuthDialog = false
            scope.launch {
                withContext(Dispatchers.IO) {
                    SpotifyRemoteManager.connect(context)
                }
                spotifyOAuthAction?.invoke()
            }
        },
        onSkip = {
            showSpotifyOAuthDialog = false
            spotifyOAuthAction?.invoke()
        }
    )
}

/**
 * Multi-select variant with default-star support.
 * Used in DefaultAppsTab — checkboxes for selection, star for default. Thin wrapper around the
 * shared [com.voxapps.apppicker.AppPickerCard], same rationale as the single-select overload above.
 */
@Composable
fun AppSelectorDropdown(
    selectedPackages: List<String>,
    defaultPackage: String?,
    onApply: (List<String>) -> Unit,
    onApplyDefault: (String?) -> Unit,
    modifier: Modifier = Modifier,
    domain: String? = null,
    label: String = "Select apps",
    filterMode: String = "all",
    extraPackages: List<String> = emptyList(),
    excludeSatellites: Boolean = true,
    satelliteDomain: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSpotifyOAuthDialog by remember { mutableStateOf(false) }
    var spotifyOAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val allApps = rememberCandidateApps(domain, extraPackages, excludeSatellites, satelliteDomain)
    val strings = rememberAppPickerStrings()

    AppPickerCard(
        apps = allApps.map { it.toPickerEntry() },
        selectedPackages = selectedPackages,
        onApply = { updated ->
            // Spotify requires OAuth before it can be newly added to the selection — checked once
            // here against the final applied list (not per-tap, since selections are staged locally
            // in the sheet now and only reach this callback once, on Done).
            val spotify = com.voxapps.commander.utils.PackageNames.SPOTIFY
            if (spotify in updated && spotify !in selectedPackages && !com.voxapps.commander.service.OAuth2Manager.isAuthorized("spotify")) {
                spotifyOAuthAction = { onApply(updated) }
                showSpotifyOAuthDialog = true
            } else {
                onApply(updated)
            }
        },
        strings = strings,
        modifier = modifier,
        label = label,
        initialFilterMode = filterMode,
        defaultPackage = defaultPackage,
        onApplyDefault = onApplyDefault
    )

    SpotifyOAuthDialog(
        show = showSpotifyOAuthDialog,
        onDismiss = { showSpotifyOAuthDialog = false },
        onConnect = {
            showSpotifyOAuthDialog = false
            scope.launch {
                withContext(Dispatchers.IO) {
                    SpotifyRemoteManager.connect(context)
                }
                spotifyOAuthAction?.invoke()
            }
        },
        onSkip = {
            showSpotifyOAuthDialog = false
            spotifyOAuthAction?.invoke()
        }
    )
}

@Composable
private fun SpotifyOAuthDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onSkip: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(languageManager?.getString("spotify_oauth_title") ?: "Spotify Login Required") },
            text = { Text(languageManager?.getString("spotify_oauth_message") ?: "Spotify requires OAuth login to enable voice-controlled playback. Connect now?") },
            confirmButton = {
                TextButton(onClick = onConnect) {
                    Text(languageManager?.getString("spotify_connect") ?: "Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = onSkip) {
                    Text(languageManager?.getString("spotify_oauth_skip") ?: "Skip")
                }
            }
        )
    }
}
