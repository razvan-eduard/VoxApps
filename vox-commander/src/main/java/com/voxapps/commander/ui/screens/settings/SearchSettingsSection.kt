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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.location.CommanderLocationStore
import com.voxapps.commander.domain.search.SearchProviderRegistry
import com.voxapps.commander.domain.search.SearchProviderRouter
import com.voxapps.commander.ui.components.ConnectionTestAuto
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
    var apiKeyRefreshKey by remember { mutableStateOf(0) }
    val providerNames = remember(categoryName, apiKeyRefreshKey) {
        SearchProviderRegistry.getAvailableProviderNames(categoryName, settingsRepo)
    }
    val defaultProvider = remember(categoryName) {
        SearchProviderRegistry.getProvider(categoryName)
    }
    var expanded by remember { mutableStateOf(false) }
    var selectedProvider by remember(categoryName, apiKeyRefreshKey) {
        mutableStateOf(defaultProvider?.name ?: providerNames.firstOrNull() ?: "")
    }

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
                    // Provider selection
                    if (providerNames.isNotEmpty()) {
                        Text(
                            text = "Providers",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        providerNames.forEach { providerName ->
                            ProviderRow(
                                providerName = providerName,
                                isSelected = providerName == selectedProvider,
                                categoryName = categoryName,
                                appStateManager = appStateManager,
                                settingsRepo = settingsRepo,
                                scope = scope,
                                context = context,
                                onSelect = { selectedProvider = it },
                                onApiKeyChanged = { apiKeyRefreshKey++ }
                            )
                        }
                    }

                    // Locked providers (require API key)
                    val lockedProviders = remember(categoryName, apiKeyRefreshKey) {
                        val all = SearchProviderRegistry.getProviderNames(categoryName)
                        all.filter { name ->
                            val provider = SearchProviderRegistry.getProvider(categoryName, name)
                            when {
                                // Shared-key providers (e.g. OpenAI) are locked based on the shared
                                // key actually applied, not the per-provider key store.
                                provider?.usesSharedApiKey == true -> !provider.hasApiKey()
                                provider?.requiresApiKey == true ->
                                    settingsRepo.getSearchProviderApiKeySync(name).isNullOrBlank()
                                else -> false
                            }
                        }
                    }
                    if (lockedProviders.isNotEmpty()) {
                        Text(
                            text = "Requires API Key",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        lockedProviders.forEach { providerName ->
                            ProviderRow(
                                providerName = providerName,
                                isSelected = false,
                                categoryName = categoryName,
                                appStateManager = appStateManager,
                                settingsRepo = settingsRepo,
                                scope = scope,
                                context = context,
                                onSelect = { selectedProvider = it },
                                onApiKeyChanged = { apiKeyRefreshKey++ }
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

@Composable
private fun ProviderRow(
    providerName: String,
    isSelected: Boolean,
    categoryName: String,
    appStateManager: com.voxapps.commander.state.AppStateManager,
    settingsRepo: com.voxapps.commander.data.preferences.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onSelect: (String) -> Unit,
    onApiKeyChanged: () -> Unit = {}
) {
    val provider = remember(categoryName, providerName) {
        SearchProviderRegistry.getProvider(categoryName, providerName)
    }
    var apiKey by remember(providerName) {
        mutableStateOf(settingsRepo.getSearchProviderApiKeySync(providerName) ?: "")
    }
    var isApiKeyFocused by remember { mutableStateOf(false) }
    val languageManager = LocalLanguageManager.current
    val uiState by appStateManager.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Radio button for selection
            RadioButton(
                selected = isSelected,
                onClick = { onSelect(providerName) }
            )

            // Provider name + info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (provider?.requiresLocation == true) {
                    Text(
                        text = "requires location",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Auto connection test — same component as Piped
                // Re-tested when the provider changes and when its credential does — a provider
                // that needs a key shows a failure until one is entered, and that failure has to
                // stop being shown the moment it is. The credential is reduced to a length, which
                // changes when the key does without the key itself becoming a composition key.
                val credentialFingerprint = if (provider?.usesSharedApiKey == true) {
                    uiState.credentials.forEngine(Strings.AiProcessors.OPENAI)?.length ?: 0
                } else {
                    settingsRepo.getSearchProviderApiKeySync(providerName)?.length ?: 0
                }
                ConnectionTestAuto(
                    keys = listOf(if (isSelected) providerName else "", credentialFingerprint),
                    testFn = { provider?.testConnection() ?: false }
                )
            }
        }

        // A shared-key provider does not own a key — it borrows the engine's. The field shown here
        // is that engine's own, writing the same slot the intent engine reads, so entering it in
        // either place is entering it once. It has to be reachable here: the engine's own screen
        // shows the field only while that engine is *selected*, and someone running a local intent
        // model would otherwise have no way to configure the provider that needs this key.
        if (provider?.usesSharedApiKey == true) {
            Text(
                text = languageManager.getString("search_provider_uses_shared_key")
                    ?: "Shares the engine's API key",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp)
            )
            EngineApiKeyField(
                engineKey = Strings.AiProcessors.OPENAI,
                appStateManager = appStateManager,
                languageManager = languageManager,
                onKeyChanged = {
                    SearchProviderRegistry.applySharedOpenAiKey(it.ifBlank { null })
                    onApiKeyChanged()
                }
            )
        }

        // API key field for providers that require their own key
        if (provider?.requiresApiKey == true && provider?.usesSharedApiKey != true) {
            TextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    scope.launch {
                    settingsRepo.setSearchProviderApiKey(providerName, it.ifBlank { null })
                    provider.setApiKey(it.ifBlank { null })
                    onApiKeyChanged()
                }
                },
                label = { Text("API Key") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isApiKeyFocused = it.isFocused },
                visualTransformation = if (isApiKeyFocused) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = !isApiKeyFocused,
                maxLines = if (isApiKeyFocused) 5 else 1,
                colors = if (!isApiKeyFocused) TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedIndicatorColor = Color.Transparent
                ) else TextFieldDefaults.colors()
            )
        }
    }
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
