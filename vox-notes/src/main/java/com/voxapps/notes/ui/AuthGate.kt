package com.voxapps.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voxapps.notes.R

/**
 * The visual barrier shown while [com.voxapps.notes.state.NotesUiState.Locked]. Auto-triggers the
 * biometric prompt once on entry; the button lets the user retry after a cancel.
 */
@Composable
fun AuthGate(onUnlockRequest: () -> Unit) {
    LaunchedEffect(Unit) { onUnlockRequest() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.locked_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            Button(onClick = onUnlockRequest, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.unlock))
            }
        }
    }
}
