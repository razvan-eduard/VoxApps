package com.voxapps.notes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.notes.R
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NoteWithCategory

/** Low-alpha tint applied to a note card's background from its category color. */
private const val CARD_TINT_ALPHA = 0.18f

private fun cardColor(category: Category?, base: Color): Color =
    category?.let { CategoryColors.fromStored(it.colorArgb).copy(alpha = CARD_TINT_ALPHA) } ?: base

/** Collapsed note: background tinted by category; title (if any) then body text. */
@Composable
fun CollapsedNoteCard(item: NoteWithCategory, onClick: () -> Unit) {
    val note = item.note
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = cardColor(item.category, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (!note.title.isNullOrBlank()) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (note.text.isNotBlank()) {
                Text(
                    note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (note.title.isNullOrBlank()) 0.dp else 4.dp)
                )
            }
        }
    }
}

/**
 * Expanded, inline note editor — the card grows as you type (title as a title, text as body). A
 * vertical color carousel on the right sets the category; the centered swatch's name shows while
 * scrolling. Done/Delete affordances at the bottom.
 */
@Composable
fun NoteEditorCard(
    title: String,
    text: String,
    categoryId: Long?,
    categories: List<Category>,
    onTitleChange: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onDone: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = cardColor(selectedCategory, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                BasicTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (title.isEmpty()) Text(
                            stringResource(R.string.note_title_optional),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        inner()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(
                            stringResource(R.string.note_text),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        inner()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.save))
                    }
                }
            }

            CategoryCarousel(
                categories = categories,
                selectedId = categoryId,
                onSelect = onCategoryChange
            )
        }
    }
}

/**
 * Vertical color carousel: "none" + one swatch per category. Tapping selects; while scrolling, the
 * name of the swatch at the top of the viewport is shown as a floating label to the left.
 */
@Composable
private fun CategoryCarousel(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    // Entry(null) = "no category".
    val entries = remember(categories) { listOf<Category?>(null) + categories }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val topName by remember {
        androidx.compose.runtime.derivedStateOf {
            val idx = listState.firstVisibleItemIndex.coerceIn(0, entries.lastIndex)
            entries[idx]?.name
        }
    }

    Box(contentAlignment = Alignment.CenterStart) {
        LazyColumn(
            state = listState,
            modifier = Modifier.width(40.dp).heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(entries, key = { it?.id ?: -1L }) { cat ->
                val color = cat?.let { CategoryColors.fromStored(it.colorArgb) }
                    ?: MaterialTheme.colorScheme.surfaceVariant
                val isSelected = cat?.id == selectedId
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else if (cat == null) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            else Modifier
                        )
                        .clickable { onSelect(cat?.id) }
                )
            }
        }

        // Name of the swatch at the top of the viewport, shown while scrolling.
        AnimatedVisibility(
            visible = listState.isScrollInProgress && topName != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.padding(end = 44.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)) {
                Text(
                    topName ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
