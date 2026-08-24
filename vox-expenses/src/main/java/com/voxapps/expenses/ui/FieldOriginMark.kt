package com.voxapps.expenses.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.recordflow.FieldOrigin

/**
 * The small mark beside a field saying where its value came from.
 *
 * A record made from a capture holds a figure the document proved, a name a list recognised, and an
 * answer a model gave — and once written they look identical. When one of them is wrong, that
 * sameness turns "the model guessed this one" into "this app is unreliable". The mark costs a
 * fourteen-pixel icon and buys the difference.
 *
 * Nothing is drawn for a field somebody typed, or for one nobody claimed: a value with no story is
 * the ordinary case, and marking it would make the marks noise.
 */
@Composable
fun FieldOriginMark(origin: FieldOrigin?, description: (FieldOrigin) -> String, modifier: Modifier = Modifier) {
    if (origin == null || origin == FieldOrigin.TYPED) return
    val icon = when (origin) {
        FieldOrigin.PROVED -> Icons.Filled.Visibility
        FieldOrigin.MATCHED -> Icons.Filled.Checklist
        FieldOrigin.ANSWERED -> Icons.Filled.AutoAwesome
        FieldOrigin.TYPED -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = description(origin),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(14.dp)
    )
}
