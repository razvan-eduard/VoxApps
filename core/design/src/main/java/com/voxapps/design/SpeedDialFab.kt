package com.voxapps.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One revealed action in a [SpeedDialFab] — [label] is shown as a chip next to its own mini FAB. */
data class SpeedDialAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

/**
 * An expand-to-reveal multi-action floating action button: tapping the main button reveals one
 * small labeled FAB per entry in [actions] stacked above it; tapping any of them collapses the menu
 * and fires that action. Used wherever a single "take a photo" tap target needs to instead offer a
 * choice of capture modes (single/stitch/batch — see [com.voxapps.ipc.VoxOcrRequest.captureMode]),
 * without every call site reimplementing the same expand/collapse shape.
 *
 * No full-screen scrim/tap-outside-to-dismiss — this composable doesn't assume it's the only thing
 * on screen (it's dropped into a `floatingActionButton` slot, or inline over a camera preview), so
 * dismissal is just tapping the main button again or picking an action.
 */
@Composable
fun SpeedDialFab(
    actions: List<SpeedDialAction>,
    modifier: Modifier = Modifier,
    mainIcon: ImageVector = Icons.Filled.Add,
    mainContentDescription: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Column(horizontalAlignment = Alignment.End) {
                actions.forEach { action ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                action.label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                expanded = false
                                action.onClick()
                            }
                        ) {
                            Icon(action.icon, contentDescription = action.label)
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { expanded = !expanded }) {
            Icon(
                if (expanded) Icons.Filled.Close else mainIcon,
                contentDescription = mainContentDescription
            )
        }
    }
}
