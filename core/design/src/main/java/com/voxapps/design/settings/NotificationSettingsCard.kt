package com.voxapps.design.settings

import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun NotificationSettingsCard(
    systemDefault: Boolean,
    vibrationEnabled: Boolean,
    soundUri: String?,
    volume: Int,
    length: String,
    onSystemDefaultChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onLengthChange: (String) -> Unit,
    onPickSound: () -> Unit,
    onPreview: (Boolean, Int?, String?, Boolean?) -> Unit,
    getString: (String) -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val soundName = remember(soundUri) {
        if (soundUri == null) {
            getString("notifications_sound_default")
        } else {
            val uri = Uri.parse(soundUri)
            RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: getString("none")
        }
    }

    val contentAlpha = if (systemDefault) 0.38f else 1f

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = getString("notifications_settings_title"), style = MaterialTheme.typography.titleMedium)

            // --- Master Switch: System Default ---
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(getString("notifications_system_default_label"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        getString("notifications_system_default_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = systemDefault,
                    onCheckedChange = {
                        onSystemDefaultChange(it)
                        if (!it) onPreview(false, null, null, null)
                    }
                )
            }

            HorizontalDivider()

            // --- Volume Slider ---
            Column(modifier = Modifier.fillMaxWidth().alpha(contentAlpha)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(getString("notifications_volume_label"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(text = "$volume%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    getString("notifications_volume_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { onVolumeChange(it.toInt()) },
                    onValueChangeFinished = { onPreview(true, volume, "SHORT", false) },
                    valueRange = 0f..100f,
                    enabled = !systemDefault,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            HorizontalDivider()

            // --- Vibration ---
            Row(
                modifier = Modifier.fillMaxWidth().alpha(contentAlpha),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(getString("notifications_vibration_label"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        getString("notifications_vibration_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        onVibrationChange(it)
                        onPreview(false, null, null, it)
                    },
                    enabled = !systemDefault
                )
            }

            HorizontalDivider()

            // --- Sound ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha)
                    .clickable(enabled = !systemDefault, onClick = onPickSound),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(getString("notifications_sound_label"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        getString("notifications_sound_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = soundName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (systemDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            HorizontalDivider()

            // --- Length Selector ---
            Column(modifier = Modifier.fillMaxWidth().alpha(contentAlpha)) {
                Text(getString("notifications_length_label"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    getString("notifications_length_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val lengths = listOf("SHORT", "MEDIUM", "LONG")
                    lengths.forEach { len ->
                        FilterChip(
                            selected = length == len,
                            onClick = { 
                                onLengthChange(len)
                                onPreview(false, null, len, null)
                            },
                            label = { Text(getString("notifications_length_${len.lowercase()}")) },
                            enabled = !systemDefault
                        )
                    }
                }
            }
        }
    }
}
