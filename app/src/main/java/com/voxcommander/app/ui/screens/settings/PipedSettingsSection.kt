package com.voxcommander.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.handler.NewPipeExtractorHelper
import com.voxcommander.app.domain.intent.handler.PipedSearchHelper
import com.voxcommander.app.domain.localization.LanguageManager
import com.voxcommander.app.ui.components.ConnectionTestAuto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipedSettingsSection(
    languageManager: LanguageManager,
    settingsRepo: SettingsRepository
) {
    val scope = rememberCoroutineScope()

    var youtubeUrlEngine by remember { mutableStateOf(settingsRepo.getYoutubeUrlEngineSync()) }
    var pipedApiUrl by remember { mutableStateOf(settingsRepo.getPipedApiUrlSync() ?: "") }
    var pipedRegion by remember { mutableStateOf(settingsRepo.getPipedRegionSync() ?: "") }

    Text(text = languageManager.getString("media_services_section"), style = MaterialTheme.typography.titleMedium)

    // YouTube URL Engine selector: Piped API or NewPipe Extractor
    val youtubeEngineOptions = listOf("piped" to "Piped API", "newpipe" to "NewPipe Extractor")
    var youtubeEngineExpanded by remember { mutableStateOf(false) }
    val youtubeEngineLabel = youtubeEngineOptions.find { it.first == youtubeUrlEngine }?.second ?: "Piped API"

    ExposedDropdownMenuBox(
        expanded = youtubeEngineExpanded,
        onExpandedChange = { youtubeEngineExpanded = !youtubeEngineExpanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = youtubeEngineLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("YouTube URL Engine") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = youtubeEngineExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = youtubeEngineExpanded, onDismissRequest = { youtubeEngineExpanded = false }) {
            youtubeEngineOptions.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(if (key == "piped") "$label (Default)" else label) },
                    onClick = {
                        youtubeUrlEngine = key
                        scope.launch { settingsRepo.setYoutubeUrlEngine(key) }
                        PipedSearchHelper.useNewPipe = key == "newpipe"
                        if (key == "newpipe") {
                            NewPipeExtractorHelper.warmUp()
                        }
                        youtubeEngineExpanded = false
                    }
                )
            }
        }
    }

    if (youtubeUrlEngine == "newpipe") {
        Text(
            text = "NewPipe Extractor uses on-device parsing (no external API). First query is slower — warming up now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        ConnectionTestAuto(
            testKey = "newpipe",
            testFn = { NewPipeExtractorHelper.testConnection() },
            testingLabel = "Testing YouTube search…",
            onlineLabel = "Connection OK",
            offlineLabel = "Connection failed"
        )
        return
    }

    val pipedInstances = PipedSearchHelper.PIPED_INSTANCES
    var pipedInstanceExpanded by remember { mutableStateOf(false) }
    val pipedInstanceLabel = if (pipedApiUrl.isBlank()) {
        languageManager.getString("piped_instance_custom")
    } else if (pipedApiUrl in pipedInstances) {
        pipedApiUrl
    } else {
        "$pipedApiUrl (${languageManager.getString("piped_instance_custom")})"
    }

    ExposedDropdownMenuBox(
        expanded = pipedInstanceExpanded,
        onExpandedChange = { pipedInstanceExpanded = !pipedInstanceExpanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = pipedInstanceLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(languageManager.getString("piped_api_url")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pipedInstanceExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = pipedInstanceExpanded, onDismissRequest = { pipedInstanceExpanded = false }) {
            DropdownMenuItem(
                text = { Text(languageManager.getString("piped_instance_custom")) },
                onClick = {
                    pipedApiUrl = ""
                    scope.launch { settingsRepo.setPipedApiUrl(null) }
                    PipedSearchHelper.setPipedApiUrl(null)
                    pipedInstanceExpanded = false
                }
            )
            pipedInstances.forEachIndexed { index, instance ->
                DropdownMenuItem(
                    text = { Text(if (index == 0) "$instance (Default)" else instance) },
                    onClick = {
                        pipedApiUrl = instance
                        scope.launch { settingsRepo.setPipedApiUrl(instance) }
                        PipedSearchHelper.setPipedApiUrl(instance)
                        pipedInstanceExpanded = false
                    }
                )
            }
        }
    }

    if (pipedApiUrl.isBlank() || pipedApiUrl !in pipedInstances) {
        var isPipedFocused by remember { mutableStateOf(false) }
        TextField(
            value = pipedApiUrl,
            onValueChange = {
                pipedApiUrl = it
                scope.launch { settingsRepo.setPipedApiUrl(it.ifBlank { null }) }
                PipedSearchHelper.setPipedApiUrl(it.ifBlank { null })
            },
            label = { Text(languageManager.getString("piped_custom_url")) },
            placeholder = { Text(languageManager.getString("piped_api_url_placeholder")) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isPipedFocused = it.isFocused },
            colors = if (!isPipedFocused) TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedIndicatorColor = Color.Transparent
            ) else TextFieldDefaults.colors()
        )
    }

    ConnectionTestAuto(
        testKey = pipedApiUrl,
        testFn = { PipedSearchHelper.testInstance(pipedApiUrl) },
        testingLabel = languageManager.getString("piped_testing"),
        onlineLabel = languageManager.getString("piped_online"),
        offlineLabel = languageManager.getString("piped_offline")
    )

    val pipedRegions = PipedSearchHelper.PIPED_REGIONS
    var pipedRegionExpanded by remember { mutableStateOf(false) }
    val pipedRegionLabel = pipedRegions.find { it.first == (pipedRegion.ifBlank { null }) }?.second
        ?: pipedRegions.first().second

    ExposedDropdownMenuBox(
        expanded = pipedRegionExpanded,
        onExpandedChange = { pipedRegionExpanded = !pipedRegionExpanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = pipedRegionLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(languageManager.getString("piped_region")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pipedRegionExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = pipedRegionExpanded, onDismissRequest = { pipedRegionExpanded = false }) {
            pipedRegions.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(if (code == null) name else "$name ($code)") },
                    onClick = {
                        pipedRegion = code ?: ""
                        scope.launch { settingsRepo.setPipedRegion(code) }
                        PipedSearchHelper.setPipedRegion(code)
                        pipedRegionExpanded = false
                    }
                )
            }
        }
    }
}
