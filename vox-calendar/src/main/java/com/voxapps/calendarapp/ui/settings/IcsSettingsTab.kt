package com.voxapps.calendarapp.ui.settings

import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.onboarding.VoxHintKeys
import com.voxapps.onboarding.VoxHintDialog
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.design.settings.SettingsSectionCard
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.domain.ics.IcsExportImportUtil
import com.voxapps.calendarapp.ui.LocalLanguageManager
import com.voxapps.calendarapp.ui.rememberIcsImportFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vox Calendar's own ICS import/export — a separate concern from Hub's JSON backup (Phase 6), for
 * interop with external calendar apps (Google/Apple/Thunderbird). The import button launches the
 * same [rememberIcsImportFlow] the sidebar's + menu offers; only the export lives here alone.
 */
@Composable
fun IcsSettingsTab(
    calendarRepository: CalendarRepository,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    VoxHintDialog(
        store = (LocalContext.current.applicationContext as CalendarApplication).container.hintStore,
        hintKey = VoxHintKeys.ICS,
        title = languageManager.getString("hint_ics_title"),
        body = languageManager.getString("hint_ics_body"),
        okLabel = languageManager.getString("hint_ok"),
        dontShowAgainLabel = languageManager.getString("hint_dont_show_again")
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launchImport = rememberIcsImportFlow(calendarRepository)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val entries = calendarRepository.entriesSnapshot().filter { it.entry.listId == null }
                val layerSnapshot = calendarRepository.layersSnapshot()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    IcsExportImportUtil.write(entries, layerSnapshot, out)
                }
            }
            Toast.makeText(context, languageManager.getString("ics_export_done"), Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionCard(languageManager.getString("ics_settings_title")) {
            Text(
                languageManager.getString("ics_settings_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { exportLauncher.launch("vox-calendar.ics") },
                modifier = Modifier.fillMaxWidth()
            ) { Text(languageManager.getString("ics_export_button")) }
            Button(
                onClick = launchImport,
                modifier = Modifier.fillMaxWidth()
            ) { Text(languageManager.getString("ics_import_button")) }
        }
    }
}
