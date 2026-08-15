package com.voxapps.notes.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.attachments.ui.AttachmentUiItem
import com.voxapps.attachments.ui.AttachmentsSection
import com.voxapps.attachments.ui.GroupDeleteConfig
import com.voxapps.attachments.ui.rememberCameraCaptureLauncher
import com.voxapps.attachments.ui.rememberVisionCaptureLauncher
import com.voxapps.attachments.ui.rememberVisionInstalled
import com.voxapps.design.SpeedDialAction
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.ipc.VoxOcrRequest
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.data.Category
import com.voxapps.notes.data.NoteWithCategory
import com.voxapps.notes.data.NotesAttachments
import com.voxapps.notes.domain.llm.LlmTasks
import com.voxapps.notes.state.NotesStateManager
import java.util.UUID
import kotlin.math.abs
import com.voxapps.design.color.VoxColorPalette

/** Low-alpha tint applied to a note card's background from its category color. */
private const val CARD_TINT_ALPHA = 0.18f

private fun cardColor(category: Category?, base: Color): Color =
    category?.let { CategoryColors.fromStored(it.colorArgb).copy(alpha = CARD_TINT_ALPHA) } ?: base

/** [factor] < 1 darkens by scaling HSV value — used for the open-for-editing note's border, so it
 *  reads as "the same category, emphasized" rather than an unrelated accent color. */
private fun Color.darker(factor: Float = 0.7f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[2] *= factor
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Collapsed note: background tinted by category; title (if any) then body text. */
@Composable
fun CollapsedNoteCard(item: NoteWithCategory, onClick: () -> Unit) {
    val note = item.note
    val context = LocalContext.current
    val attachmentDao = remember { (context.applicationContext as NotesApplication).container.attachmentDao }
    val hasAttachments by remember(note.id) {
        attachmentDao.observeFor(NotesAttachments.RECORD_TYPE, note.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = cardColor(item.category, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (!note.title.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (hasAttachments.isNotEmpty()) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp).padding(start = 4.dp)
                        )
                    }
                }
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
    noteId: Long?,
    stateManager: NotesStateManager,
    title: String,
    text: String,
    categoryId: Long?,
    categories: List<Category>,
    pendingAttachments: List<String>,
    onTitleChange: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onAddCategory: (name: String, colorArgb: Long, onResult: (Long) -> Unit) -> Unit,
    onPendingAttachmentsChange: (List<String>) -> Unit,
    onDone: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val languageManager = LocalLanguageManager.current
    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    // Nothing otherwise visually sets the open-for-editing note apart from the flat collapsed cards
    // below it in the same list — a border in a darker shade of its own category color reads as
    // "this category's note, emphasized" rather than an unrelated accent color, and still says
    // something sensible when there's no category (darkened surfaceVariant).
    val borderColor = selectedCategory
        ?.let { CategoryColors.fromStored(it.colorArgb) }
        ?.darker()
        ?: MaterialTheme.colorScheme.outline
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .border(2.dp, borderColor, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(
            containerColor = cardColor(selectedCategory, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top row: the collapse pill stays centered; Delete/Save move up here (previously at
            // the bottom of the text column) so they read as this card's primary actions at a
            // glance, with Attachments now occupying the bottom instead (see below).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
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
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
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
                }

                // Vertical coverflow category picker on the right edge.
                CategoryCoverflow(
                    categories = categories,
                    selectedId = categoryId,
                    onSelect = onCategoryChange,
                    onAddCategory = onAddCategory
                )
            }

            // At the bottom now that Delete/Save moved up to the top row. A brand-new draft has no
            // id yet to scope real AttachmentEntity rows against, so it stages files locally instead
            // (PendingNoteAttachmentsHost) — NotesScreen links them to the real note once it's saved,
            // or deletes the staged files if the draft is discarded instead.
            if (noteId != null) {
                NoteAttachmentsHost(noteId, stateManager)
            } else {
                PendingNoteAttachmentsHost(pendingAttachments, onPendingAttachmentsChange)
            }
        }
    }
}

/** Resolves [noteId]'s attachment rows into display items and wires add/remove to
 *  [NotesStateManager], including staging a newly-picked photo into this app's own files dir before
 *  recording it. Split out from [NoteEditorCard] so that composable's own signature stays scannable. */
@Composable
private fun NoteAttachmentsHost(noteId: Long, stateManager: NotesStateManager) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val entities by stateManager.observeAttachments(noteId).collectAsStateWithLifecycle(initialValue = emptyList())
    val items = remember(entities) {
        val groupSizes = entities.mapNotNull { it.groupId }.groupingBy { it }.eachCount()
        entities.map { e ->
            val groupSize = e.groupId?.let { groupSizes[it] } ?: 0
            AttachmentUiItem(
                id = e.id,
                uri = AttachmentFileStore.uriFor(context, NotesAttachments.FILE_PROVIDER_AUTHORITY, NotesAttachments.DIR, e.fileName),
                removable = e.source == AttachmentSource.MANUAL,
                groupLabel = if (groupSize > 1) "${e.groupOrder + 1}/$groupSize" else null,
                groupKey = e.groupId,
                groupSource = e.source
            )
        }
    }
    fun handlePickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val groupId = if (uris.size > 1) UUID.randomUUID().toString() else null
        uris.forEachIndexed { index, uri ->
            AttachmentFileStore.stage(context, uri, NotesAttachments.DIR)?.let { fileName ->
                stateManager.addManualAttachment(noteId, fileName, groupId, index)
            }
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> handlePickedUris(uris) }
    // Zero attachments yet: single + stitch (a stitch group is one document, whole-group delete
    // only — see AttachmentUiItem.groupSource). Already has attachments: single + batch, each new
    // photo independent (Notes never runs OCR/LLM on attachments at all — see LlmTasks.
    // NOTE_ATTACHMENT_CAPTURE's doc comment, so "batch" here differs from Expenses' only in that
    // there's no rescan-suggestion step, just independent staging).
    val takePhotoSingle = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.NOTE_ATTACHMENT_CAPTURE}:$noteId", hint = null, produceOCR = false,
        captureMode = VoxOcrRequest.CAPTURE_MODE_SINGLE
    )
    val takePhotoStitch = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.NOTE_ATTACHMENT_CAPTURE}:$noteId", hint = null, produceOCR = false,
        captureMode = VoxOcrRequest.CAPTURE_MODE_STITCH
    )
    val takePhotoBatch = rememberVisionCaptureLauncher(
        baseTask = "${LlmTasks.NOTE_ATTACHMENT_CAPTURE}:$noteId", hint = null, produceOCR = false,
        captureMode = VoxOcrRequest.CAPTURE_MODE_BATCH
    )
    // Attaching a photo never requires Vision: plain system camera always first, Vision's
    // cropped-document modes (crop-rectangle icon) only while Vision is installed.
    val visionInstalled = rememberVisionInstalled()
    val takeStandardPhoto = rememberCameraCaptureLauncher(NotesAttachments.FILE_PROVIDER_AUTHORITY) { uri ->
        handlePickedUris(listOf(uri))
    }
    val captureActions = listOf(
        SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("attachment_take_photo"), takeStandardPhoto)
    )
    val visionActions = buildList {
        if (visionInstalled) {
            add(SpeedDialAction(Icons.Filled.Crop, languageManager.getString("capture_mode_single"), takePhotoSingle))
            if (items.isEmpty()) {
                add(SpeedDialAction(Icons.Filled.Layers, languageManager.getString("capture_mode_stitch"), takePhotoStitch))
            } else {
                add(SpeedDialAction(Icons.Filled.BurstMode, languageManager.getString("capture_mode_batch"), takePhotoBatch))
            }
        }
    }
    AttachmentsSection(
        title = languageManager.getString("attachments"),
        items = items,
        canAdd = items.count { it.removable } < 10,
        onPickFromGallery = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        captureActions = captureActions,
        visionActions = visionActions,
        galleryLabel = languageManager.getString("attachment_choose_gallery"),
        cancelLabel = languageManager.getString("cancel"),
        removeConfirmTitle = languageManager.getString("delete_attachment_title"),
        removeConfirmMessage = languageManager.getString("delete_attachment_message"),
        removeConfirmLabel = languageManager.getString("delete"),
        onRemove = { item ->
            entities.firstOrNull { it.id == item.id }?.let { stateManager.removeAttachment(it, context) }
        },
        groupDelete = GroupDeleteConfig(
            onDeleteGroup = { groupId -> stateManager.deleteAttachmentGroup(noteId, groupId, context) },
            confirmTitle = languageManager.getString("delete_attachment_group_title"),
            confirmMessage = languageManager.getString("delete_attachment_group_message"),
            confirmLabel = languageManager.getString("delete"),
            cancelLabel = languageManager.getString("cancel")
        )
    )
}

/** Attachments UI for a not-yet-saved note: stages picked photos into this app's files dir via
 *  [AttachmentFileStore] immediately (no note id needed for that), but only tracks them as local
 *  filenames — no [AttachmentEntity] row exists until [NotesScreen] links them once the note is
 *  actually saved (or deletes the staged files if the draft is discarded instead). A fake id derived
 *  from the filename's hash is enough for [AttachmentsSection]'s list key — it never needs a real
 *  database id. */
@Composable
private fun PendingNoteAttachmentsHost(pendingAttachments: List<String>, onChange: (List<String>) -> Unit) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val items = remember(pendingAttachments) {
        pendingAttachments.map { fileName ->
            AttachmentUiItem(
                id = fileName.hashCode().toLong(),
                uri = AttachmentFileStore.uriFor(context, NotesAttachments.FILE_PROVIDER_AUTHORITY, NotesAttachments.DIR, fileName),
                removable = true
            )
        }
    }
    fun handlePickedUri(uri: Uri?) {
        if (uri != null) {
            AttachmentFileStore.stage(context, uri, NotesAttachments.DIR)?.let { fileName ->
                onChange(pendingAttachments + fileName)
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> handlePickedUri(uri) }
    val takePhoto = rememberCameraCaptureLauncher(NotesAttachments.FILE_PROVIDER_AUTHORITY) { uri -> handlePickedUri(uri) }
    AttachmentsSection(
        title = languageManager.getString("attachments"),
        items = items,
        canAdd = pendingAttachments.size < 10,
        onPickFromGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        // Draft (not-yet-saved) note attachments still use the plain system camera, not Vision —
        // out of scope for the single/stitch/batch speed dial (no OCR/record concept exists yet for
        // a draft), so this is just the one existing "take photo" action, unchanged behavior.
        captureActions = listOf(SpeedDialAction(Icons.Filled.PhotoCamera, languageManager.getString("attachment_take_photo"), takePhoto)),
        galleryLabel = languageManager.getString("attachment_choose_gallery"),
        cancelLabel = languageManager.getString("cancel"),
        removeConfirmTitle = languageManager.getString("delete_attachment_title"),
        removeConfirmMessage = languageManager.getString("delete_attachment_message"),
        removeConfirmLabel = languageManager.getString("delete"),
        onRemove = { item ->
            pendingAttachments.firstOrNull { it.hashCode().toLong() == item.id }?.let { fileName ->
                AttachmentFileStore.delete(context, NotesAttachments.DIR, fileName)
                onChange(pendingAttachments - fileName)
            }
        }
    )
}

/** One row of the coverflow: the fixed "no category" swatch, one per real [Category], or the
 *  trailing "add a new category" swatch — a sealed type rather than reusing `Category?` since the
 *  add-new entry isn't a selectable category at all, it opens a dialog instead. */
private sealed interface CoverflowEntry {
    data object None : CoverflowEntry
    data class Existing(val category: Category) : CoverflowEntry
    data object AddNew : CoverflowEntry
}

/**
 * Vertical "coverflow" category picker: a snapping scroller of color swatches on the right. The swatch
 * nearest the vertical center is the selection — enlarged/opaque, its neighbors shrink and fade. The
 * centered category's name shows to the left. Tapping a swatch snaps it to center + selects it. The
 * last swatch is a "+" that opens an inline new-category dialog instead of selecting anything;
 * confirming it both creates the category and selects it on the note being edited.
 */
@Composable
private fun CategoryCoverflow(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onAddCategory: (name: String, colorArgb: Long, onResult: (Long) -> Unit) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    var showAddDialog by remember { mutableStateOf(false) }
    val entries = remember(categories) {
        listOf(CoverflowEntry.None) + categories.map(CoverflowEntry::Existing) + listOf(CoverflowEntry.AddNew)
    }
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

    // Re-centers on the current selection whenever it changes from outside a user scroll too (e.g.
    // right after creating+selecting a brand-new category), not just on first show.
    LaunchedEffect(selectedId, entries) {
        val idx = entries.indexOfFirst { it is CoverflowEntry.Existing && it.category.id == selectedId }.coerceAtLeast(0)
        listState.scrollToItem(idx)
    }

    // Commit the centered swatch as the selection once a scroll settles (skip the initial state).
    // Landing on "add new" opens the dialog instead of selecting — it's never a real selection.
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (wasScrolling && !scrolling) {
                when (val entry = entries.getOrNull(centeredIndex)) {
                    is CoverflowEntry.Existing -> onSelect(entry.category.id)
                    CoverflowEntry.None -> onSelect(null)
                    CoverflowEntry.AddNew, null -> showAddDialog = true
                }
            }
            wasScrolling = scrolling
        }
    }

    val centeredName = when (val entry = entries.getOrNull(centeredIndex)) {
        is CoverflowEntry.Existing -> entry.category.name
        CoverflowEntry.None -> languageManager.getString("none")
        CoverflowEntry.AddNew, null -> languageManager.getString("add_category")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = centeredName,
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
            itemsIndexed(
                entries,
                key = { _, entry ->
                    when (entry) {
                        is CoverflowEntry.Existing -> entry.category.id
                        CoverflowEntry.None -> -1L
                        CoverflowEntry.AddNew -> -2L
                    }
                }
            ) { index, entry ->
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                val dist = itemInfo?.let { abs((it.offset + it.size / 2f) - center) } ?: (halfViewportPx * 2)
                val norm = (1f - dist / halfViewportPx).coerceIn(0f, 1f)
                val scale = 0.55f + 0.55f * norm
                val isSelected = entry is CoverflowEntry.Existing && entry.category.id == selectedId
                Box(
                    modifier = Modifier.height(itemHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when (entry) {
                        is CoverflowEntry.Existing -> Box(
                            modifier = Modifier
                                .size(34.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 0.35f + 0.65f * norm }
                                .clip(CircleShape)
                                .background(CategoryColors.fromStored(entry.category.colorArgb))
                                .then(
                                    if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { onSelect(entry.category.id) }
                        )
                        CoverflowEntry.None -> Box(
                            modifier = Modifier
                                .size(34.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 0.35f + 0.65f * norm }
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { onSelect(null) }
                        )
                        CoverflowEntry.AddNew -> Box(
                            modifier = Modifier
                                .size(34.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 0.35f + 0.65f * norm }
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { showAddDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = languageManager.getString("add_category"),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        NewCategoryFromNoteDialog(
            existingColors = categories.map { it.colorArgb },
            onDismiss = { showAddDialog = false },
            onConfirm = { name, color ->
                onAddCategory(name, color) { newId -> if (newId > 0) onSelect(newId) }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun NewCategoryFromNoteDialog(existingColors: List<Long>, onDismiss: () -> Unit, onConfirm: (String, Long) -> Unit) {
    val languageManager = LocalLanguageManager.current
    var name by remember { mutableStateOf("") }
    var selectedColor by remember(existingColors) { mutableLongStateOf(VoxColorPalette.unusedOrRandomColor(existingColors)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(languageManager.getString("add_category")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(languageManager.getString("category_name")) },
                    singleLine = true
                )
                VoxColorSwatchPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    customColorDialogTitle = languageManager.getString("custom_color_title"),
                    customColorUseLabel = languageManager.getString("use_color_button"),
                    customColorCancelLabel = languageManager.getString("cancel"),
                    customColorHueLabel = languageManager.getString("hue_label"),
                    customColorSaturationLabel = languageManager.getString("saturation_label"),
                    customColorBrightnessLabel = languageManager.getString("brightness_label")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedColor) },
                enabled = name.isNotBlank()
            ) { Text(languageManager.getString("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(languageManager.getString("cancel")) }
        }
    )
}
