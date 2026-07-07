package com.voxapps.notes.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.notes.R
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.data.preferences.NotesSettingsRepository
import com.voxapps.notes.state.NotesStateManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    stateManager: NotesStateManager,
    settingsRepo: NotesSettingsRepository,
    onBack: () -> Unit
) {
    val settings by settingsRepo.settingsFlow.collectAsStateWithLifecycle(initialValue = NotesSettings())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { pad ->
        GeneralSettingsTab(
            settings = settings,
            stateManager = stateManager,
            modifier = Modifier.fillMaxSize().padding(pad)
        )
    }
}
