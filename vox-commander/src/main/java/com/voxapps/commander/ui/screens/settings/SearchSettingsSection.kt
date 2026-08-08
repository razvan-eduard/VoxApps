package com.voxapps.commander.ui.screens.settings

import com.voxapps.location.VoxNominatimGeocoder
import com.voxapps.location.CachedCoordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.commander.ui.LocalLanguageManager
import com.voxapps.commander.ui.components.EngineApiKeyField
import com.voxapps.commander.utils.Strings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.location.CommanderLocationStore
import com.voxapps.commander.domain.search.SearchProviderRegistry
import com.voxapps.commander.domain.search.SearchProviderRouter
import com.voxapps.commander.ui.components.ConnectionTestCard
import com.voxapps.commander.ui.components.CredentialField
import com.voxapps.commander.ui.components.SettingsPicklist
import com.voxapps.location.LocationSource
import com.voxapps.location.ResolvedLocation
import com.voxapps.location.VoxLocationResolver
import com.voxapps.location.ui.VoxLocationSettingsCard
import com.voxapps.location.ui.VoxLocationUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSettingsSection(
    appStateManager: com.voxapps.commander.state.AppStateManager,

    settingsRepo: com.voxapps.commander.data.preferences.SettingsRepository
) {
        val languageManager = LocalLanguageManager.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val categories = SearchProviderRegistry.categories

    Text(text = languageManager.getString("search_section"), style = MaterialTheme.typography.titleMedium)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CommanderLocationSettingsSection(
            settingsRepo = settingsRepo,
            scope = scope,
            context = context
        )

        categories.forEach { category ->
            CategoryNode(
                categoryName = category,

                appStateManager = appStateManager,
                settingsRepo = settingsRepo,
                scope = scope,
                context = context
            )
        }
    }
}

@Composable
private fun CommanderLocationSettingsSection(
    settingsRepo: com.voxapps.commander.data.preferences.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
    val store = remember(settingsRepo) { CommanderLocationStore(context, settingsRepo) }
    // needsReverseGeocode = true: Commander gains Nominatim place names for the "last known
    // location" display, matching what vox-expenses already resolves for its city field.
    val resolver = remember(store) { VoxLocationResolver.create(context, store, needsReverseGeocode = true) }

    var homeTown by remember { mutableStateOf(store.getHomeTownSync()) }
    var cacheTtl by remember { mutableStateOf(store.getCacheTtlSync()) }
    var alwaysUse by remember { mutableStateOf(store.getAlwaysUseHomeTownSync()) }
    var lastLocation by remember {
        mutableStateOf(
            store.getCachedLocationSync()?.let { ResolvedLocation(it.lat, it.lon, LocationSource.CACHE, it.resolvedName) }
        )
    }
    var isRefreshing by remember { mutableStateOf(false) }

    /*
     * The screen always shows a town for the fix it is showing.
     *
     * The town is wanted here and nowhere else, so it is resolved here — the search paths cache
     * coordinates without one on purpose, to keep a Nominatim round trip out of a spoken query.
     * This fills the gap, and writes the answer back so the next visit costs nothing.
     *
     * Resolved once and never re-resolved: a place name describes the coordinates it was fetched
     * for, so it cannot go stale while they do not change. What expires is the *fix* — and when the
     * cache lets it expire, the next resolve replaces the coordinates and the name together. Asking
     * Nominatim again for a name we already hold would spend their usage policy on redrawing a
     * screen.
     */
    LaunchedEffect(lastLocation?.lat, lastLocation?.lon, lastLocation?.displayName) {
        val current = lastLocation ?: return@LaunchedEffect
        if (current.displayName != null) return@LaunchedEffect

        val name = withContext(Dispatchers.IO) {
            runCatching { VoxNominatimGeocoder().reverseGeocode(current.lat, current.lon) }.getOrNull()
        } ?: return@LaunchedEffect

        lastLocation = current.copy(displayName = name)
        val cached = store.getCachedLocationSync()
        store.setCachedLocation(
            CachedCoordinate(
                lat = current.lat,
                lon = current.lon,
                // The fix's own age is preserved: naming it is not re-taking it, and pretending
                // otherwise would keep an old fix alive past the cache life the user chose.
                timestampMillis = cached?.timestampMillis ?: System.currentTimeMillis(),
                resolvedName = name
            )
        )
    }

    VoxLocationSettingsCard(
        state = VoxLocationUiState(
            lastKnownLocation = lastLocation,
            homeTown = homeTown,
            cacheTtl = cacheTtl,
            alwaysUseHomeTown = alwaysUse,
            isRefreshing = isRefreshing
        ),
        onHomeTownChange = { newHomeTown ->
            homeTown = newHomeTown
            scope.launch { store.setHomeTown(newHomeTown) }
        },
        onCacheTtlChange = { ttl ->
            cacheTtl = ttl
            scope.launch { store.setCacheTtl(ttl) }
        },
        onAlwaysUseHomeTownChange = { enabled ->
            alwaysUse = enabled
            scope.launch { VoxLocationResolver.setAlwaysUseHomeTown(store, enabled) }
        },
        onRefreshClick = {
            isRefreshing = true
            scope.launch {
                lastLocation = resolver.resolveLocation()
                isRefreshing = false
            }
        }
    )
}

@Composable
private fun CategoryNode(
    categoryName: String,

    appStateManager: com.voxapps.commander.state.AppStateManager,
    settingsRepo: com.voxapps.commander.data.preferences.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
    val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    val providerNames = remember(categoryName) {
        SearchProviderRegistry.getProviderNames(categoryName)
    }
    // What answers this category: the stored choice, or the schema's default until one is made.
    val defaultProviderName = remember(categoryName, providerNames) {
        SearchProviderRegistry.getProvider(categoryName)?.name.orEmpty()
    }
    val selectedProvider = uiState.searchProviderSelections[categoryName] ?: defaultProviderName
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column {
            // Category header row (clickable to expand/collapse)
            Surface(
                onClick = { expanded = !expanded },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = categoryName.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (providerNames.isNotEmpty()) {
                            Text(
                                text = "${providerNames.size} provider${if (providerNames.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (selectedProvider.isNotBlank()) {
                        Text(
                            text = selectedProvider,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Expanded content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    /*
                     * One dropdown per category, exactly like the engine screens.
                     *
                     * Every declared provider is listed and every one can be chosen, including those
                     * still missing a key — the key field appears directly beneath the selection, so
                     * choosing one is how you get to configure it. The old screen split them into a
                     * usable list and a locked "Requires API Key" list, which put the fix for being
                     * locked in a second place and made the two lists look like different kinds of
                     * thing.
                     */
                    if (providerNames.isNotEmpty()) {
                        SettingsPicklist(
                            items = providerNames,
                            selected = selectedProvider,
                            itemLabel = { it },
                            onSelect = { appStateManager.setSearchProvider(categoryName, it) },
                            itemNote = { name ->
                                if (needsCredential(categoryName, name, uiState)) " — needs an API key" else ""
                            }
                        ) {
                            SelectedProviderDetails(
                                categoryName = categoryName,
                                providerName = selectedProvider,
                                appStateManager = appStateManager,
                                settingsRepo = settingsRepo
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Manual query test
                    ManualQueryTest(
                        categoryName = categoryName,
                        providerName = selectedProvider,
                        settingsRepo = settingsRepo,
                        scope = scope,
                        context = context
                    )
                }
            }
        }
    }
}

/** True when a provider says it needs a key and the store has none for it. */
private fun needsCredential(
    categoryName: String,
    providerName: String,
    uiState: com.voxapps.commander.state.AppState
): Boolean {
    val provider = SearchProviderRegistry.getProvider(categoryName, providerName) ?: return false
    if (!provider.requiresApiKey) return false
    return if (provider.usesSharedApiKey) !uiState.credentials.has(Strings.AiProcessors.OPENAI)
    else uiState.credentials.forSearchProvider(providerName) == null
}

/**
 * Whatever the chosen provider needs said about it: where it gets its key, and whether it answers.
 *
 * Under the collapsed dropdown rather than on the rows, so opening the menu costs no requests — a
 * test per row would be one request per provider every time someone looked at the list.
 */
@Composable
private fun SelectedProviderDetails(
    categoryName: String,
    providerName: String,
    appStateManager: com.voxapps.commander.state.AppStateManager,
    settingsRepo: com.voxapps.commander.data.preferences.SettingsRepository
) {
    val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()
    val provider = remember(categoryName, providerName) {
        SearchProviderRegistry.getProvider(categoryName, providerName)
    } ?: return

    val credential = if (provider.usesSharedApiKey) {
        uiState.credentials.forEngine(Strings.AiProcessors.OPENAI)
    } else {
        uiState.credentials.forSearchProvider(providerName)
    }

    if (provider.requiresLocation) {
        Text(
            text = "requires location",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // A shared-key provider does not own a key — it borrows the engine's. The field shown here is
    // that engine's own, writing the same slot the intent engine reads, so entering it in either
    // place is entering it once. It has to be reachable here: the engine's own screen shows the
    // field only while that engine is *selected*, and someone running a local intent model would
    // otherwise have no way to configure the provider that needs this key.
    if (provider.usesSharedApiKey) {
        Text(
            text = languageManager.getString("search_provider_uses_shared_key")
                ?: "Shares the engine's API key",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        EngineApiKeyField(
            engineKey = Strings.AiProcessors.OPENAI,
            appStateManager = appStateManager,
            languageManager = languageManager,
            onKeyChanged = { SearchProviderRegistry.applySharedOpenAiKey(it.ifBlank { null }) }
        )
    } else if (provider.requiresApiKey) {
        CredentialField(
            stored = credential ?: "",
            label = languageManager.getString("engine_api_key"),
            identity = providerName,
            onCommit = { appStateManager.setSearchProviderApiKey(providerName, it.ifBlank { null }) }
        )
    }

    // The credential the screen holds is what gets tested, rather than whatever copy the registry
    // was last pushed — the test then answers for the key that is on screen.
    val spec = remember(providerName, credential, uiState.language) {
        provider.probeSpec(uiState.language)?.copy(credential = credential)
    }
    ConnectionTestCard(spec = spec, settingsRepo = settingsRepo)
}

@Composable
private fun ManualQueryTest(
    categoryName: String,
    providerName: String,
    settingsRepo: com.voxapps.commander.data.preferences.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
        val languageManager = LocalLanguageManager.current
    var testQuery by remember { mutableStateOf("") }
    var testResults by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    Text(
        text = languageManager.getString("search_test_query") ?: "Test Query",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        OutlinedTextField(
            value = testQuery,
            onValueChange = { testQuery = it },
            label = { Text(languageManager.getString("search_test_placeholder") ?: "Enter query...") },
            singleLine = true,
            enabled = !isSearching,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = {
                if (testQuery.isBlank()) return@Button
                isSearching = true
                testResults = ""
                scope.launch {
                    var lat: Double? = null
                    var lon: Double? = null
                    val activeProvider = if (providerName.isNotBlank())
                        SearchProviderRegistry.getProvider(categoryName, providerName)
                    else SearchProviderRegistry.getProvider(categoryName)

                    if (activeProvider?.requiresLocation == true) {
                        val loc = VoxLocationResolver.create(context, CommanderLocationStore(context, settingsRepo)).resolveLocation()
                        if (loc != null) {
                            lat = loc.lat
                            lon = loc.lon
                        } else {
                            testResults = "Location unavailable. Grant location permission."
                            isSearching = false
                            return@launch
                        }
                    }

                    val results = if (activeProvider != null && providerName.isNotBlank()) {
                        activeProvider.search(testQuery, lat, lon)
                    } else {
                        SearchProviderRouter.search(testQuery, categoryName, lat, lon)
                    }
                    testResults = if (results.isEmpty()) {
                        "No results found"
                    } else {
                        SearchProviderRouter.formatResultsForSummary(testQuery, results)
                    }
                    isSearching = false
                }
            },
            enabled = !isSearching && testQuery.isNotBlank()
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (testResults.isNotBlank()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = testResults,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
                maxLines = 15,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
