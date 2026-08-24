package com.voxapps.design.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A long list of things, folded away behind how many of them are doing anything.
 *
 * A supplied list runs to dozens of entries and pushes everything under it off the screen, while the
 * only question a person has about it most of the time is "how much of this is on?". Collapsed, the
 * count answers that and costs one line; expanded, the list is exactly what it was.
 *
 * Open when it is short enough to read at a glance, folded when it is not — a list of three that
 * makes you tap to see three is a list that has learned the wrong lesson from a list of fifty. The
 * state lives in composition rather than in settings: where a section was left is not a preference,
 * and storing it would make a scroll position outlive the app.
 */
@Composable
fun VoxCountedSection(
    label: String,
    activeCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
    /** Above this many entries the section starts folded. */
    foldAbove: Int = 8,
    /** Shown in the header beside the count — a "turn all of these off" action, typically. Hidden
     *  while folded, since it acts on rows nobody can see. */
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    var expanded by remember(totalCount <= foldAbove) { mutableStateOf(totalCount <= foldAbove) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            // Active out of total, always — the number that changes when you switch something off
            // is the number worth reading without opening anything.
            Text(
                "$activeCount / $totalCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) trailing()
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}
