package com.voxapps.commander.ui.screens.settings

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.handler.NewPipeExtractorHelper
import com.voxapps.commander.domain.intent.handler.PipedSearchHelper
import com.voxapps.commander.domain.media.MediaServiceRegistry
import com.voxapps.commander.domain.engine.CloudDeadline
import com.voxapps.services.ServiceProbe
import com.voxapps.commander.ui.components.ConnectionTestCard
import com.voxapps.design.SettingsPicklist
import kotlinx.coroutines.launch
import com.voxapps.design.CommittedTextField

private const val REGION_DEFAULT_KEY = "piped_region_system_default"

/**
 * Which backend answers "play this", and how to reach it.
 *
 * Everything on this screen used to be written out in Kotlin: the two backends as a pair of
 * literals, the Piped instances as a list of four URLs, the regions as a list of fifty. They are
 * declared now, so an instance that dies is replaced by the schema rather than by a release —
 * which is the usual reason a public instance list goes stale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipedSettingsSection(

    settingsRepo: SettingsRepository
) {
    val languageManager = LocalLanguageManager.current
    val scope = rememberCoroutineScope()

    val backends = remember { MediaServiceRegistry.backends() }
    var youtubeUrlEngine by remember { mutableStateOf(settingsRepo.getYoutubeUrlEngineSync()) }
    var pipedApiUrl by remember { mutableStateOf(settingsRepo.getPipedApiUrlSync() ?: "") }
    var pipedRegion by remember { mutableStateOf(settingsRepo.getPipedRegionSync() ?: "") }

    Text(text = languageManager.getString("media_services_section"), style = MaterialTheme.typography.titleMedium)

    Text(
        text = "YouTube URL Engine",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    val selectedBackend = MediaServiceRegistry.byId(youtubeUrlEngine)
        ?: backends.firstOrNull { it.isDefault }
        ?: backends.firstOrNull()

    SettingsPicklist(
        items = backends,
        selected = selectedBackend,
        itemLabel = { if (it.isDefault) "${it.label} (Default)" else it.label },
        onSelect = { backend ->
            youtubeUrlEngine = backend.id
            scope.launch { settingsRepo.setYoutubeUrlEngine(backend.id) }
            PipedSearchHelper.useNewPipe = backend.isBuiltIn
            if (backend.isBuiltIn) NewPipeExtractorHelper.warmUp()
        }
    ) {
        /*
         * A backend with no endpoint still gets a test, because it still has a way to fail.
         *
         * NewPipe parses YouTube on device, so what breaks is the parsing rather than the network,
         * and no HTTP status can report that — its test runs a real search and looks at the
         * results. Routed through the same prober so it is bounded and logged like every other
         * test, and shown in the same card so the screen does not need to know the difference.
         */
        if (selectedBackend?.isBuiltIn == true) {
            Text(
                text = "NewPipe Extractor uses on-device parsing (no external API). First query is slower — warming up now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            ConnectionTestCard(
                // Nothing configurable to depend on, so the retry button is the only way to ask again.
                keys = listOf(selectedBackend.id),
                testFn = {
                    ServiceProbe.run(selectedBackend.id, CloudDeadline.secondsFor(selectedBackend.id, settingsRepo)) {
                        NewPipeExtractorHelper.testConnection()
                    }
                },
                testingLabel = "Testing YouTube search…",
                onlineLabel = "Connection OK",
                offlineLabel = "Connection failed"
            )
        }
    }

    if (selectedBackend?.isBuiltIn == true) return

    val backendId = selectedBackend?.id ?: PipedSearchHelper.BACKEND_ID
    val instances = remember(backendId) { MediaServiceRegistry.endpoints(backendId) }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = languageManager.getString("piped_api_url"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // A blank entry is the custom one: the picklist offers the declared instances, and anything the
    // user types goes in the field below rather than into the list.
    val instanceOptions = remember(instances) { listOf("") + instances }
    val customLabel = languageManager.getString("piped_instance_custom")
    var customChosen by remember(instances) {
        mutableStateOf(pipedApiUrl.isNotBlank() && pipedApiUrl !in instances)
    }

    SettingsPicklist(
        items = instanceOptions,
        // With nothing stored, the first declared instance is what answers — so that is what the
        // button says. Showing "custom" for an unconfigured default named the one thing it was not.
        selected = when {
            customChosen -> ""
            pipedApiUrl in instances -> pipedApiUrl
            else -> instances.firstOrNull().orEmpty()
        },
        itemLabel = { instance ->
            when {
                instance.isBlank() && pipedApiUrl.isNotBlank() && pipedApiUrl !in instances ->
                    "$pipedApiUrl ($customLabel)"
                instance.isBlank() -> customLabel
                instance == instances.firstOrNull() -> "$instance (Default)"
                else -> instance
            }
        },
        onSelect = { instance ->
            customChosen = instance.isBlank()
            // Choosing "custom" opens the field without discarding what is already stored; only a
            // declared instance replaces it.
            if (instance.isNotBlank()) {
                pipedApiUrl = instance
                scope.launch { settingsRepo.setPipedApiUrl(instance) }
                PipedSearchHelper.setPipedApiUrl(instance)
            }
        }
    ) {
        ConnectionTestCard(
            spec = MediaServiceRegistry.probeSpecFor(backendId, pipedApiUrl),
            settingsRepo = settingsRepo,
            testingLabel = languageManager.getString("piped_testing"),
            onlineLabel = languageManager.getString("piped_online"),
            offlineLabel = languageManager.getString("piped_offline")
        )
    }

    if (customChosen) {
        // Stored when the field is finished with. Per keystroke it stored a partial URL and, since
        // the test below is keyed on the URL it would probe, sent a request per character to hosts
        // spelled half way.
        CommittedTextField(
            stored = pipedApiUrl,
            label = languageManager.getString("piped_custom_url"),
            placeholder = languageManager.getString("piped_api_url_placeholder"),
            identity = backendId,
            onCommit = { entered ->
                pipedApiUrl = entered
                scope.launch { settingsRepo.setPipedApiUrl(entered.ifBlank { null }) }
                PipedSearchHelper.setPipedApiUrl(entered.ifBlank { null })
            }
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = languageManager.getString("piped_region"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Blank is the system default — a place the schema has no reason to name.
    val regions = remember(backendId) { MediaServiceRegistry.regions(backendId) }
    val regionCodes = remember(regions) { listOf("") + regions.map { it.code } }
    // getString echoes an unknown key back, so an untranslated build would show the key itself.
    val systemDefaultLabel = languageManager.getString(REGION_DEFAULT_KEY)
        .takeIf { it != REGION_DEFAULT_KEY } ?: "System Default"

    SettingsPicklist(
        items = regionCodes,
        selected = pipedRegion,
        itemLabel = { code ->
            if (code.isBlank()) systemDefaultLabel
            else regions.firstOrNull { it.code == code }?.let { "${it.label} (${it.code})" } ?: code
        },
        onSelect = { code ->
            pipedRegion = code
            scope.launch { settingsRepo.setPipedRegion(code.ifBlank { null }) }
            PipedSearchHelper.setPipedRegion(code.ifBlank { null })
        }
    )
}
