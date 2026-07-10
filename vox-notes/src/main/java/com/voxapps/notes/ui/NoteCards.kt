package com.voxapps.notes.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NoteWithCategory
import kotlin.math.abs

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
    val languageManager = LocalLanguageManager.current
    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = cardColor(selectedCategory, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Collapse affordance: its own centered row above everything else, in a shadowed
            // pill so it reads as a distinct floating control rather than blending into the title.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    onClick = onDone,
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    tonalElevation = 2.dp
                ) {
                    Icon(
                        Icons.Filled.ExpandLess,
                        contentDescription = languageManager.getString("collapse_note"),
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        BasicTextField(
                            value = title,
                            onValueChange = onTitleChange,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                if (title.isEmpty()) Text(
                                    languageManager.getString("note_title_optional"),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                inner()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (text.isEmpty()) Text(
                                languageManager.getString("note_text"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            inner()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(top = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onDelete != null) {
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
                            }
                        }
                        IconButton(onClick = onDone) {
                            Icon(Icons.Filled.Check, contentDescription = languageManager.getString("save"))
                        }
                    }
                }

                // Vertical coverflow category picker on the right edge.
                CategoryCoverflow(
                    categories = categories,
                    selectedId = categoryId,
                    onSelect = onCategoryChange
                )
            }
        }
    }
}

/**
 * Vertical "coverflow" category picker: a snapping scroller of color swatches on the right. The swatch
 * nearest the vertical center is the selection — enlarged/opaque, its neighbors shrink and fade. The
 * centered category's name shows to the left. Tapping a swatch snaps it to center + selects it.
 */
@Composable
private fun CategoryCoverflow(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val entries = remember(categories) { listOf<Category?>(null) + categories }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val itemHeight = 44.dp
    val viewportHeight = 176.dp
    val halfViewportPx = with(density) { viewportHeight.toPx() / 2f }

    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2f) - center) }?.index ?: 0
        }
    }

    // Center the current selection on first show.
    LaunchedEffect(Unit) {
        val idx = entries.indexOfFirst { it?.id == selectedId }.coerceAtLeast(0)
        listState.scrollToItem(idx)
    }

    // Commit the centered swatch as the selection once a scroll settles (skip the initial state).
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (wasScrolling && !scrolling) onSelect(entries.getOrNull(centeredIndex)?.id)
            wasScrolling = scrolling
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = entries.getOrNull(centeredIndex)?.name ?: languageManager.getString("none"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp).padding(end = 6.dp)
        )
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            modifier = Modifier.height(viewportHeight).width(48.dp),
            contentPadding = PaddingValues(vertical = (viewportHeight - itemHeight) / 2),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(entries, key = { _, it -> it?.id ?: -1L }) { index, cat ->
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                val dist = itemInfo?.let { abs((it.offset + it.size / 2f) - center) } ?: (halfViewportPx * 2)
                val norm = (1f - dist / halfViewportPx).coerceIn(0f, 1f)
                val scale = 0.55f + 0.55f * norm
                val color = cat?.let { CategoryColors.fromStored(it.colorArgb) }
                    ?: MaterialTheme.colorScheme.surfaceVariant
                val isSelected = cat?.id == selectedId
                Box(
                    modifier = Modifier.height(itemHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 0.35f + 0.65f * norm }
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
        }
    }
}
