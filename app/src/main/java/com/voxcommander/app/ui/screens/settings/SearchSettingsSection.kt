package com.voxcommander.app.ui.screens.settings

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
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.domain.search.SearchProviderRegistry
import com.voxcommander.app.domain.search.SearchProviderRouter
import com.voxcommander.app.ui.components.ConnectionTestAuto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSettingsSection(
    languageManager: LanguageManager,
    settingsRepo: com.voxcommander.app.data.preferences.SettingsRepository
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val categories = SearchProviderRegistry.categories

    Text(text = languageManager.getString("search_section"), style = MaterialTheme.typography.titleMedium)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Manual location fallback
        ManualLocationSetting(
            settingsRepo = settingsRepo,
            scope = scope
        )

        categories.forEach { category ->
            CategoryNode(
                categoryName = category,
                languageManager = languageManager,
                settingsRepo = settingsRepo,
                scope = scope,
                context = context
            )
        }
    }
}

@Composable
private fun ManualLocationSetting(
    settingsRepo: com.voxcommander.app.data.preferences.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var latText by remember {
        mutableStateOf(settingsRepo.getManualLocationLatSync()?.toString() ?: "")
    }
    var lonText by remember {
        mutableStateOf(settingsRepo.getManualLocationLonSync()?.toString() ?: "")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Manual Location (Fallback)",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Used when GPS is unavailable and no cached location exists. Useful for weather search.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Latitude") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("Longitude") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val lat = latText.toDoubleOrNull()
                        val lon = lonText.toDoubleOrNull()
                        scope.launch { settingsRepo.setManualLocation(lat, lon) }
                    },
                    enabled = latText.isNotBlank() && lonText.isNotBlank()
                ) {
                    Text("Save")
                }
                if (settingsRepo.getManualLocationLatSync() != null) {
                    TextButton(
                        onClick = {
                            latText = ""
                            lonText = ""
                            scope.launch { settingsRepo.setManualLocation(null, null) }
                        }
                    ) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryNode(
    categoryName: String,
    languageManager: LanguageManager,
    settingsRepo: com.voxcommander.app.data.preferences.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
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
                            provider?.requiresApiKey == true &&
                                settingsRepo.getSearchProviderApiKeySync(name).isNullOrBlank()
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
                        languageManager = languageManager,
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
    settingsRepo: com.voxcommander.app.data.preferences.SettingsRepository,
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
                ConnectionTestAuto(
                    testKey = if (isSelected) providerName else "",
                    testFn = { provider?.testConnection() ?: false }
                )
            }
        }

        // API key field for providers that require it
        if (provider?.requiresApiKey == true) {
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
    languageManager: LanguageManager,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
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
                        val loc = com.voxcommander.app.domain.search.LocationHelper.getLocation(context)
                        if (loc != null) {
                            lat = loc.latitude
                            lon = loc.longitude
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
