package com.voxapps.calendarapp.ui.settings

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
import com.voxapps.calendarapp.data.CalendarRepository
import com.voxapps.calendarapp.domain.ics.IcsExportImportUtil
import com.voxapps.calendarapp.ui.LocalLanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vox Calendar's own ICS import/export — a separate concern from Hub's JSON backup (Phase 6), for
 * interop with external calendar apps (Google/Apple/Thunderbird).
 */
@Composable
fun IcsSettingsTab(
    calendarRepository: CalendarRepository,
    modifier: Modifier = Modifier
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val entries = calendarRepository.entriesSnapshot()
                val layers = calendarRepository.layersSnapshot()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    IcsExportImportUtil.write(entries, layers, out)
                }
            }
            Toast.makeText(context, languageManager.getString("ics_export_done"), Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                val parsed = context.contentResolver.openInputStream(uri)?.use { input ->
                    IcsExportImportUtil.read(input)
                } ?: emptyList()
                IcsExportImportUtil.importEntries(calendarRepository, parsed)
                parsed.size
            }
            Toast.makeText(
                context,
                String.format(languageManager.getString("ics_import_done"), count),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(languageManager.getString("ics_settings_title"), style = MaterialTheme.typography.titleMedium)
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
            onClick = { importLauncher.launch(arrayOf("text/calendar", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(languageManager.getString("ics_import_button")) }
    }
}
