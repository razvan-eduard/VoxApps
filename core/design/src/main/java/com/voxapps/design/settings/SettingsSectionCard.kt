package com.voxapps.design.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One logical group of settings, raised and titled, on a screen that is the end of a menu path.
 *
 * A page you have already navigated to has no navigating left to do, so its job is only to present
 * a handful of decisions clearly. Three devices were competing to do that — coloured bands, thin
 * rules, and bare spacing — and using them together made the page look busier the more carefully it
 * was organised.
 *
 * One device instead. A group is a raised surface with its own title, every card padded the same
 * inside and spaced the same from its neighbours, so the eye learns the shape once and stops having
 * to. Nothing else divides: no bands, no rules between entries of one list.
 *
 * A section that already arrives as a card of its own — the ones in this package that own their
 * layout — is left alone rather than wrapped in a second one, since two raised surfaces around the
 * same content read as a mistake.
 *
 * [SettingsSectionHeader] remains for the menu screens that lead here, where the entries are
 * navigation rather than settings and a card around each would be a card around a single line.
 */
@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // Card rather than ElevatedCard because only this one takes a border; the elevated surface and
    // shadow are asked for explicitly, so the result is the raised card plus a traced edge.
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        // A drawn edge as well as a lift. Elevation alone is a shadow, and a shadow disappears on a
        // dark theme against a dark surface — the card then reads as a slightly different rectangle
        // rather than as a container. The accent traces the shape at low opacity so the boundary
        // survives either theme, and the raised elevation puts a soft halo under it.
        border = BorderStroke(BORDER_WIDTH, MaterialTheme.colorScheme.primary.copy(alpha = BORDER_ALPHA)),
        colors = CardDefaults.elevatedCardColors(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = CARD_ELEVATION)
    ) {
        Column(
            modifier = Modifier.padding(CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(CARD_PADDING)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

/** The same inside every card and between every pair of them, so the rhythm is learned once. */
private val CARD_PADDING = 16.dp

/** Enough to trace the shape, not enough to become an outline the eye reads before the contents. */
private val BORDER_WIDTH = 1.dp
private const val BORDER_ALPHA = 0.35f

/** The halo. High enough to lift the card off the page, low enough that a column of them does not
 *  look like a stack of floating tiles. */
private val CARD_ELEVATION = 6.dp
