package com.voxapps.design.selection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp

/**
 * The bar that replaces a screen's own while a selection is being made.
 *
 * Taking the top bar's place rather than sitting under it is the point: it says the screen is in a
 * different mode, and it puts the way out — one ✕, where the screen's own title was — where a person
 * already looks for it. What it offers is the caller's; what it guarantees is that leaving is always
 * one tap, and always in the same place, in every app that does this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxSelectionBar(
    count: Int,
    title: (Int) -> String,
    onClose: () -> Unit,
    closeContentDescription: String,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = { Text(title(count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = closeContentDescription)
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
}

/**
 * A row that can be picked out of a list: tap does whatever tapping does, holding starts choosing.
 *
 * The long press is what makes the mode discoverable at all, and the outline is what makes it
 * readable — a chosen row has to be legible as chosen from across the list, without a checkbox
 * column that would occupy every row for a mode nobody is in most of the time.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.voxSelectable(
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier = composed {
    this
        .then(
            if (selected) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            } else {
                Modifier
            }
        )
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
}
