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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.builder.TextContextMenuBuilderScope
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import com.voxapps.notes.domain.localization.LanguageManager
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
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
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.attachments.VoiceNotePlayer
import com.voxapps.attachments.ui.rememberVoiceMemoLauncher
import com.voxapps.notes.domain.InlineMedia
import com.voxapps.notes.domain.NoteBlock
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
            if (InlineMedia.hasMedia(note.textHtml)) {
                // A journal entry: the list card IS the journal, so it renders the real thing —
                // styled runs, thumbnails, playable voice pills.
                JournalBody(
                    textHtml = note.textHtml!!,
                    modifier = Modifier.padding(top = if (note.title.isNullOrBlank()) 0.dp else 4.dp)
                )
            } else if (note.text.isNotBlank()) {
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
    textHtml: String?,
    categoryId: Long?,
    categories: List<Category>,
    pendingAttachments: List<String>,
    onTitleChange: (String) -> Unit,
    onContentChange: (plain: String, html: String) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onAddCategory: (name: String, colorArgb: Long, onResult: (Long) -> Unit) -> Unit,
    onPendingAttachmentsChange: (List<String>) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val languageManager = LocalLanguageManager.current
    var editorExtraHeightPx by remember(noteId) { mutableStateOf(0f) }
    var editorMaxed by remember(noteId) { mutableStateOf(false) }
    var isFullscreen by remember(noteId) { mutableStateOf(false) }
    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    // Nothing otherwise visually sets the open-for-editing note apart from the flat collapsed cards
    // below it in the same list — a border in a darker shade of its own category color reads as
    // "this category's note, emphasized" rather than an unrelated accent color, and still says
    // something sensible when there's no category (darkened surfaceVariant).
    val borderColor = selectedCategory
        ?.let { CategoryColors.fromStored(it.colorArgb) }
        ?.darker()
        ?: MaterialTheme.colorScheme.outline
    // One editor body for both homes: the inline card and the fullscreen dialog. A ColumnScope
    // receiver so the fullscreen branch can weight the writing area to fill; exactly one instance
    // composes at a time, so the rich state never has a twin fighting it over the buffer.
    val editorFields: @Composable ColumnScope.(fullscreen: Boolean) -> Unit = { fullscreen ->

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

                // The note's body is a journal from here on: rich-text runs interleaved with
                // media rows, derived from the stored HTML's markers (InlineMedia). Each text
                // run owns its own rich state; the bar acts on whichever run holds focus, and
                // every change leaves as the pair the buffer stores — plain text (with 📷/🎤
                // placeholders standing in for media) and the joined HTML.
                val context = LocalContext.current
                val blocks = remember(noteId) {
                    mutableStateListOf<EditorBlock>().also { list ->
                        InlineMedia.splitBlocks(textHtml ?: plainTextAsHtml(text))
                            .forEach { list.add(it.toEditorBlock()) }
                        if (list.none { it is EditorBlock.Text }) list.add(EditorBlock.Text(RichTextState()))
                    }
                }
                var focusedTextKey by remember(noteId) {
                    mutableStateOf(blocks.filterIsInstance<EditorBlock.Text>().firstOrNull()?.key)
                }
                val focusedText = blocks.filterIsInstance<EditorBlock.Text>()
                    .let { texts -> texts.firstOrNull { it.key == focusedTextKey } ?: texts.first() }
                LaunchedEffect(blocks) {
                    snapshotFlow {
                        blocks.toList().map { b -> if (b is EditorBlock.Text) b.state.annotatedString else b }
                    }.collect {
                        onContentChange(editorPlainText(blocks), InlineMedia.joinBlocks(blocks.map { it.toNoteBlock() }))
                    }
                }

                // Media landing at the cursor: the marker goes into the focused run, then that
                // run's own HTML is re-split — the marker paragraph falls out as a new block.
                fun insertMedia(marker: String) {
                    val target = focusedText
                    target.state.insertHtmlAfterSelection(marker)
                    val parts = InlineMedia.splitBlocks(target.state.toHtml())
                    val index = blocks.indexOf(target)
                    if (index < 0) return
                    blocks.removeAt(index)
                    val replacements = parts.map { it.toEditorBlock() }
                        .ifEmpty { listOf(EditorBlock.Text(RichTextState())) }
                    replacements.forEachIndexed { i, b -> blocks.add(index + i, b) }
                    // Typing continues after the media: if nothing follows it yet, give it a run.
                    if (blocks.lastOrNull() !is EditorBlock.Text) blocks.add(EditorBlock.Text(RichTextState()))
                    focusedTextKey = blocks.filterIsInstance<EditorBlock.Text>().lastOrNull()?.key
                }

                fun removeMediaBlock(block: EditorBlock) {
                    val index = blocks.indexOf(block)
                    if (index < 0) return
                    blocks.removeAt(index)
                    // The file itself lives until save (reconcile) or discard decides its fate.
                    val previous = blocks.getOrNull(index - 1) as? EditorBlock.Text
                    val next = blocks.getOrNull(index) as? EditorBlock.Text
                    if (previous != null && next != null) {
                        previous.state.setHtml(previous.state.toHtml() + next.state.toHtml())
                        blocks.removeAt(index)
                        if (focusedTextKey == next.key) focusedTextKey = previous.key
                    }
                }

                val pickInlinePhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) stageInlinePhoto(context, uri)?.let { insertMedia(it) }
                }
                // Some devices ship no PICK_IMAGES handler at all (AOSP images without the photo
                // picker) — the documents UI behind GetContent is the one picker that always exists.
                val pickInlinePhotoFallback = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) stageInlinePhoto(context, uri)?.let { insertMedia(it) }
                }
                val takeInlinePhoto = rememberCameraCaptureLauncher(NotesAttachments.FILE_PROVIDER_AUTHORITY) { uri ->
                    stageInlinePhoto(context, uri)?.let { insertMedia(it) }
                }
                val recordVoice = rememberVoiceMemoLauncher(
                    dirName = NotesAttachments.DIR,
                    recordingLabel = languageManager.getString("voice_recording"),
                    stopLabel = languageManager.getString("voice_stop"),
                    cancelLabel = languageManager.getString("cancel"),
                    onPermissionDenied = {
                        android.widget.Toast.makeText(
                            context, languageManager.getString("voice_permission_denied"), android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    onRecorded = { fileName, durationMs -> insertMedia(InlineMedia.buildVoiceMarker(fileName, durationMs)) }
                )
                RichFormatBar(
                    state = focusedText.state,
                    onPickPhoto = {
                        runCatching {
                            pickInlinePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }.onFailure { pickInlinePhotoFallback.launch("image/*") }
                    },
                    onTakePhoto = takeInlinePhoto,
                    onRecordVoice = recordVoice
                )
                // The writing room the handle at the card's foot controls: dragged, it grows
                // by exactly the drag; tapped, it jumps to most of the screen and back to
                // whatever size it had. A minimum only — content taller than the room still
                // grows the card the way it always did.
                val editorMinHeight = if (editorMaxed) {
                    (LocalConfiguration.current.screenHeightDp * 0.55f).dp
                } else {
                    DEFAULT_EDITOR_MIN_HEIGHT + with(LocalDensity.current) { editorExtraHeightPx.toDp() }
                }
                val isBodyEmpty = blocks.size == 1 &&
                    (blocks[0] as? EditorBlock.Text)?.state?.annotatedString?.text?.isEmpty() == true
                Column(
                    modifier = if (fullscreen) {
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp)
                    } else {
                        Modifier.fillMaxWidth().heightIn(min = editorMinHeight).padding(top = 4.dp)
                    }
                ) {
                    blocks.forEach { block ->
                        when (block) {
                            is EditorBlock.Text -> Box(modifier = Modifier.fillMaxWidth()) {
                                if (isBodyEmpty) {
                                    Text(
                                        languageManager.getString("note_text"),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                BasicRichTextEditor(
                                    state = block.state,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { if (it.isFocused) focusedTextKey = block.key }
                                        .appendTextContextMenuComponents {
                                            richStyleContextMenuItems(block.state, languageManager)
                                        }
                                )
                            }
                            is EditorBlock.Photo -> InlinePhotoRow(
                                fileName = block.fileName,
                                width = block.width,
                                height = block.height,
                                onRemove = { removeMediaBlock(block) }
                            )
                            is EditorBlock.Voice -> InlineVoiceRow(
                                fileName = block.fileName,
                                durationLabel = block.durationLabel,
                                onRemove = { removeMediaBlock(block) }
                            )
                        }
                    }
                }
    }

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
            // Top row. Delete sits alone on the left, where destroying something should not
            // neighbour saving it; the centered pill is the fullscreen switch (its inverse twin
            // inside the fullscreen dialog is the way back); the right side closes — X leaves
            // without writing, the check saves, both exactly what they do everywhere else.
            EditorTopRow(
                fullscreen = false,
                onToggleFullscreen = { isFullscreen = true },
                onDismiss = onDismiss,
                onDone = onDone,
                onDelete = onDelete,
                languageManager = languageManager
            )

            if (!isFullscreen) Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    editorFields(false)
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
            if (!isFullscreen) {
            if (noteId != null) {
                NoteAttachmentsHost(noteId, stateManager)
            } else {
                PendingNoteAttachmentsHost(pendingAttachments, onPendingAttachmentsChange)
            }

            // The resize handle: a grip on the card's bottom edge. Dragging takes manual control
            // of the writing room above; tapping toggles between most of the screen and the size
            // the drag (or the default) had set — the manual size is remembered, not reset.
            val maxExtraPx = with(LocalDensity.current) { MAX_EDITOR_EXTRA_HEIGHT.toPx() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { editorMaxed = !editorMaxed }
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            editorMaxed = false
                            editorExtraHeightPx = (editorExtraHeightPx + dragAmount).coerceIn(0f, maxExtraPx)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                )
            }
            }
        }
    }

    // Fullscreen: the same fields, the whole screen, and deliberately fewer ways out — the
    // inverse arrow returns to the card, X leaves without writing, the check saves; deleting a
    // note is not a thing done from the room you write it in. The system back key is the arrow.
    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxSize()) {
                    EditorTopRow(
                        fullscreen = true,
                        onToggleFullscreen = { isFullscreen = false },
                        onDismiss = onDismiss,
                        onDone = onDone,
                        onDelete = null,
                        languageManager = languageManager
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        editorFields(true)
                    }
                }
            }
        }
    }
}

/**
 * The editor's action strip, both modes. Delete alone at the left (absent in fullscreen — and for
 * drafts, which have nothing to delete), the fullscreen switch centered — up-arrow to enter,
 * inverse arrow to leave — and leaving at the right: X without writing, check saving.
 */
@Composable
private fun EditorTopRow(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onDelete: (() -> Unit)?,
    languageManager: com.voxapps.notes.domain.localization.LanguageManager
) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp)) {
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Filled.Delete, contentDescription = languageManager.getString("delete"))
            }
        }
        Surface(
            onClick = onToggleFullscreen,
            shape = CircleShape,
            shadowElevation = 4.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(
                if (fullscreen) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = languageManager.getString(
                    if (fullscreen) "editor_exit_fullscreen" else "editor_fullscreen"
                ),
                modifier = Modifier.padding(6.dp)
            )
        }
        Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = languageManager.getString("editor_dismiss"))
            }
            IconButton(onClick = onDone) {
                Icon(Icons.Filled.Check, contentDescription = languageManager.getString("save"))
            }
        }
    }
}

/** The writing room a fresh editor opens with — the size a tap on the handle shrinks back to
 *  when nothing was dragged. */
private val DEFAULT_EDITOR_MIN_HEIGHT = 80.dp

/** How much room dragging can add. A ceiling, not a target — past this the tap-to-max is the
 *  honest gesture, and an unbounded drag can push the card's own controls off screen. */
private val MAX_EDITOR_EXTRA_HEIGHT = 600.dp

/** Plain text carried into the rich editor: escaped, line breaks kept. The inverse direction is
 *  the editor's own — its HTML is stored beside the plain text it exports. */
private fun plainTextAsHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

/**
 * The editor's working form of a [com.voxapps.notes.domain.NoteBlock]: text runs get a live
 * [RichTextState], media rows carry just what their composable draws. [key] keeps focus tracking
 * and list identity stable while blocks are spliced around an insert or a delete.
 */
private sealed interface EditorBlock {
    val key: String

    class Text(val state: RichTextState, override val key: String = UUID.randomUUID().toString()) : EditorBlock
    class Photo(
        val fileName: String, val width: Int, val height: Int,
        override val key: String = fileName
    ) : EditorBlock
    class Voice(
        val fileName: String, val durationLabel: String,
        override val key: String = fileName
    ) : EditorBlock
}

private fun NoteBlock.toEditorBlock(): EditorBlock = when (this) {
    is NoteBlock.Text -> EditorBlock.Text(RichTextState().apply { setHtml(html) })
    is NoteBlock.Photo -> EditorBlock.Photo(fileName, width, height)
    is NoteBlock.Voice -> EditorBlock.Voice(fileName, durationLabel)
}

private fun EditorBlock.toNoteBlock(): NoteBlock = when (this) {
    is EditorBlock.Text -> NoteBlock.Text(state.toHtml())
    is EditorBlock.Photo -> NoteBlock.Photo(fileName, width, height)
    is EditorBlock.Voice -> NoteBlock.Voice(fileName, durationLabel)
}

/** What the journal stores as its plain text: each run's own text, media as their placeholders —
 *  see [InlineMedia.PHOTO_PLACEHOLDER]'s doc for why these lines must exist. */
private fun editorPlainText(blocks: List<EditorBlock>): String =
    blocks.joinToString(separator = "\n") { block ->
        when (block) {
            is EditorBlock.Text -> block.state.annotatedString.text
            is EditorBlock.Photo -> InlineMedia.PHOTO_PLACEHOLDER
            is EditorBlock.Voice -> block.durationLabel
        }
    }.trim()

/** Stages a picked/captured photo and returns its ready-to-insert marker, sized off the real
 *  image's own aspect ratio (bounds-only decode — the pixels stay on disk). */
private fun stageInlinePhoto(context: android.content.Context, uri: Uri): String? {
    val fileName = AttachmentFileStore.stage(context, uri, NotesAttachments.DIR) ?: return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(
        AttachmentFileStore.file(context, NotesAttachments.DIR, fileName).absolutePath, bounds
    )
    val (width, height) = InlineMedia.thumbDimensions(bounds.outWidth, bounds.outHeight)
    return InlineMedia.buildPhotoMarker(fileName, width, height)
}

/** An inline photo's row: the thumbnail at its marker's size, an ✕ while editing. */
@Composable
private fun InlinePhotoRow(
    fileName: String,
    width: Int,
    height: Int,
    onRemove: (() -> Unit)?
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val file = remember(fileName) { AttachmentFileStore.file(context, NotesAttachments.DIR, fileName) }
    if (!file.exists()) {
        MissingMediaRow()
        return
    }
    Box(modifier = Modifier.padding(vertical = 4.dp)) {
        coil.compose.AsyncImage(
            model = file,
            contentDescription = languageManager.getString("attachments"),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .width(width.dp)
                .height(height.dp)
                .clip(MaterialTheme.shapes.medium)
        )
        if (onRemove != null) {
            Surface(
                onClick = onRemove,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = languageManager.getString("delete"),
                    modifier = Modifier.padding(3.dp).size(14.dp)
                )
            }
        }
    }
}

/** An inline voice note's row: a pill mini-player — play/stop state follows
 *  [VoiceNotePlayer.playingFileName] — plus an ✕ while editing. */
@Composable
private fun InlineVoiceRow(
    fileName: String,
    durationLabel: String,
    onRemove: (() -> Unit)?
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val file = remember(fileName) { AttachmentFileStore.file(context, NotesAttachments.DIR, fileName) }
    if (!file.exists()) {
        MissingMediaRow()
        return
    }
    val playingFile by VoiceNotePlayer.playingFileName.collectAsStateWithLifecycle()
    val isPlaying = playingFile == fileName
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Surface(
            onClick = { VoiceNotePlayer.toggle(context, file) },
            shape = CircleShape,
            color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = languageManager.getString("rich_insert_voice"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    durationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = languageManager.getString("delete"),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** What a marker renders when its file is not on this device — the P2P-synced-note case, where
 *  rows travel but media files do not. */
@Composable
private fun MissingMediaRow() {
    val languageManager = LocalLanguageManager.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(
            Icons.Filled.HideImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            languageManager.getString("media_missing"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** How high a journal card's body may grow in the list before it clips — roughly a dozen text
 *  lines' worth, media rows included. */
private val JOURNAL_CARD_MAX_BODY_HEIGHT = 320.dp

/**
 * The collapsed card's body for a note that carries inline media: the same block structure the
 * editor works on, read-only — styled text runs, real thumbnails, live mini-players. Clipped at
 * [JOURNAL_CARD_MAX_BODY_HEIGHT]; opening the note shows the rest.
 */
@Composable
private fun JournalBody(textHtml: String, modifier: Modifier = Modifier) {
    val blocks = remember(textHtml) { InlineMedia.splitBlocks(textHtml) }
    Column(modifier = modifier.heightIn(max = JOURNAL_CARD_MAX_BODY_HEIGHT).clipToBounds()) {
        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Text -> {
                    val state = remember(block.html) { RichTextState().apply { setHtml(block.html) } }
                    com.mohamedrejeb.richeditor.ui.material3.RichText(
                        state = state,
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                is NoteBlock.Photo -> InlinePhotoRow(block.fileName, block.width, block.height, onRemove = null)
                is NoteBlock.Voice -> InlineVoiceRow(block.fileName, block.durationLabel, onRemove = null)
            }
        }
    }
}

/** The sizes the bar cycles through — named steps rather than a number field, because a note is
 *  styled by eye, not typeset. */
private val RICH_TEXT_SIZES = listOf(14.sp, 18.sp, 24.sp, 32.sp)

/**
 * Puts the four character styles into the platform's own text-selection menu (after its
 * cut/copy entries), so styling stays reachable even while that floating menu covers the
 * format bar. The bar's flat toggles remain the second, chainable path to the same calls.
 */
private fun TextContextMenuBuilderScope.richStyleContextMenuItems(
    state: RichTextState,
    languageManager: LanguageManager
) {
    separator()
    item(key = "rich_bold", label = languageManager.getString("rich_bold")) {
        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        close()
    }
    item(key = "rich_italic", label = languageManager.getString("rich_italic")) {
        state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        close()
    }
    item(key = "rich_underline", label = languageManager.getString("rich_underline")) {
        state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
        close()
    }
    item(key = "rich_strikethrough", label = languageManager.getString("rich_strikethrough")) {
        state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        close()
    }
}

/**
 * The small format bar the editor carries: bold, italic, underline, strikethrough as toggles that
 * read their pressed state off the cursor's own style, then size, font and ink. Size and font
 * cycle through fixed steps; ink is one dot per color plus a reset. Everything acts through
 * [RichTextState.toggleSpanStyle]-family calls, on the selection when there is one and on what is
 * typed next when there is not.
 */
@Composable
private fun RichFormatBar(
    state: RichTextState,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVoice: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val current = state.currentSpanStyle
    var insertMenuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp)
    ) {
        // The journal "+": photo or voice note, dropped at the cursor as a row of its own.
        Box {
            FormatToggle(
                icon = Icons.Filled.AddCircleOutline,
                active = insertMenuOpen,
                description = languageManager.getString("rich_insert_media")
            ) { insertMenuOpen = true }
            DropdownMenu(expanded = insertMenuOpen, onDismissRequest = { insertMenuOpen = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                DropdownMenuItem(
                    text = { Text(languageManager.getString("attachment_choose_gallery")) },
                    leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                    onClick = { insertMenuOpen = false; onPickPhoto() }
                )
                DropdownMenuItem(
                    text = { Text(languageManager.getString("attachment_take_photo")) },
                    leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    onClick = { insertMenuOpen = false; onTakePhoto() }
                )
                DropdownMenuItem(
                    text = { Text(languageManager.getString("rich_insert_voice")) },
                    leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    onClick = { insertMenuOpen = false; onRecordVoice() }
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Character styles under one trigger — except while text is selected: a selection means
        // "I'm about to style this", so the four toggles lay themselves flat for one-tap use
        // right when they're wanted (the platform's own selection menu keeps clipboard duty).
        val hasSelection = !state.selection.collapsed
        if (hasSelection) {
            FormatToggle(Icons.Filled.FormatBold, current.fontWeight == FontWeight.Bold,
                languageManager.getString("rich_bold")) {
                state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            FormatToggle(Icons.Filled.FormatItalic, current.fontStyle == FontStyle.Italic,
                languageManager.getString("rich_italic")) {
                state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            }
            FormatToggle(Icons.Filled.FormatUnderlined,
                current.textDecoration?.contains(TextDecoration.Underline) == true,
                languageManager.getString("rich_underline")) {
                state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
            }
            FormatToggle(Icons.Filled.FormatStrikethrough,
                current.textDecoration?.contains(TextDecoration.LineThrough) == true,
                languageManager.getString("rich_strikethrough")) {
                state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
            }
        } else {
            var styleMenuOpen by remember { mutableStateOf(false) }
            Box {
                FormatToggle(
                    icon = Icons.Filled.FormatBold,
                    active = styleMenuOpen || current.fontWeight == FontWeight.Bold ||
                        current.fontStyle == FontStyle.Italic || current.textDecoration != null,
                    description = languageManager.getString("rich_text_styles")
                ) { styleMenuOpen = true }
                DropdownMenu(expanded = styleMenuOpen, onDismissRequest = { styleMenuOpen = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                    StyleMenuItem(Icons.Filled.FormatBold, languageManager.getString("rich_bold"),
                        current.fontWeight == FontWeight.Bold) {
                        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    }
                    StyleMenuItem(Icons.Filled.FormatItalic, languageManager.getString("rich_italic"),
                        current.fontStyle == FontStyle.Italic) {
                        state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    }
                    StyleMenuItem(Icons.Filled.FormatUnderlined, languageManager.getString("rich_underline"),
                        current.textDecoration?.contains(TextDecoration.Underline) == true) {
                        state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                    }
                    StyleMenuItem(Icons.Filled.FormatStrikethrough, languageManager.getString("rich_strikethrough"),
                        current.textDecoration?.contains(TextDecoration.LineThrough) == true) {
                        state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    }
                }
            }
        }

        // Lists are paragraph-level: the toggle takes the section the cursor sits in (or the
        // selected paragraphs) in and out of bullets or numbering, and the two exclude each other
        // — the library moves a numbered paragraph straight to bullets rather than stacking them.
        var listMenuOpen by remember { mutableStateOf(false) }
        Box {
            FormatToggle(
                icon = Icons.Filled.FormatListBulleted,
                active = listMenuOpen || state.isUnorderedList || state.isOrderedList,
                description = languageManager.getString("rich_lists")
            ) { listMenuOpen = true }
            DropdownMenu(expanded = listMenuOpen, onDismissRequest = { listMenuOpen = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                StyleMenuItem(Icons.Filled.FormatListBulleted, languageManager.getString("rich_bullets"),
                    state.isUnorderedList) { state.toggleUnorderedList() }
                StyleMenuItem(Icons.Filled.FormatListNumbered, languageManager.getString("rich_numbering"),
                    state.isOrderedList) { state.toggleOrderedList() }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Size: a list like every other group; each row previews its own size, and the trigger's
        // own "Aa" grows with the current step so the state is readable at a glance.
        var sizeMenuOpen by remember { mutableStateOf(false) }
        val sizeDescription = languageManager.getString("rich_size")
        val currentSizeIndex = RICH_TEXT_SIZES.indexOfFirst { it == current.fontSize }.coerceAtLeast(0)
        Box {
            val triggerActive = sizeMenuOpen || currentSizeIndex > 0
            Surface(
                onClick = { sizeMenuOpen = true },
                shape = CircleShape,
                color = if (triggerActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                    Text(
                        "Aa",
                        fontSize = listOf(12.sp, 14.sp, 16.sp, 19.sp)[currentSizeIndex],
                        fontWeight = FontWeight.Medium,
                        color = if (triggerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = sizeDescription }
                    )
                }
            }
            DropdownMenu(
                expanded = sizeMenuOpen,
                onDismissRequest = { sizeMenuOpen = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = false)
            ) {
                RICH_TEXT_SIZES.forEachIndexed { index, size ->
                    DropdownMenuItem(
                        text = { Text("Aa", fontSize = size) },
                        trailingIcon = if (index == currentSizeIndex) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            state.addSpanStyle(SpanStyle(fontSize = size))
                            sizeMenuOpen = false
                        }
                    )
                }
            }
        }
        // Font: the three families every renderer knows, each row previewing itself.
        var fontMenuOpen by remember { mutableStateOf(false) }
        Box {
            FormatToggle(
                icon = Icons.Filled.TextFormat,
                active = fontMenuOpen || (current.fontFamily != null && current.fontFamily != FontFamily.SansSerif),
                description = languageManager.getString("rich_font")
            ) { fontMenuOpen = true }
            DropdownMenu(
                expanded = fontMenuOpen,
                onDismissRequest = { fontMenuOpen = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = false)
            ) {
                listOf(
                    FontFamily.SansSerif to languageManager.getString("rich_font_sans"),
                    FontFamily.Serif to languageManager.getString("rich_font_serif"),
                    FontFamily.Monospace to languageManager.getString("rich_font_mono")
                ).forEach { (family, label) ->
                    val selected = current.fontFamily == family ||
                        (family == FontFamily.SansSerif && current.fontFamily == null)
                    DropdownMenuItem(
                        text = { Text(label, fontFamily = family) },
                        trailingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            state.addSpanStyle(SpanStyle(fontFamily = family))
                            fontMenuOpen = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Ink under one trigger, and the picker is the same swatch component every category/layer
        // color choice already uses (shared palette + its custom-color dialog) — one color
        // language across the app. A color put on words stays content: it keeps its exact value
        // whatever theme the note is later read under.
        var colorMenuOpen by remember { mutableStateOf(false) }
        Box {
            FormatToggle(
                icon = Icons.Filled.Palette,
                active = colorMenuOpen || (current.color != Color.Unspecified && current.color != Color.Black),
                description = languageManager.getString("rich_ink")
            ) { colorMenuOpen = true }
            DropdownMenu(expanded = colorMenuOpen, onDismissRequest = { colorMenuOpen = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                // Fixed on BOTH axes: the menu measures its content with intrinsics, and the
                // picker's LazyRow (a SubcomposeLayout) cannot answer those — a fully fixed
                // wrapper short-circuits the query before it ever reaches the list.
                Box(modifier = Modifier.width(280.dp).height(64.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    VoxColorSwatchPicker(
                        // The cursor without ink reads as Unspecified, which has no ARGB — the
                        // category flow never sees this because a category always has a color.
                        selectedColor = if (current.color == Color.Unspecified) 0L
                        else VoxColorPalette.toStored(current.color),
                        onColorSelected = { stored ->
                            state.addSpanStyle(SpanStyle(color = VoxColorPalette.fromStored(stored)))
                        },
                        collapsible = false,
                        swatchSize = 32.dp,
                        customColorDialogTitle = languageManager.getString("custom_color_title"),
                        customColorUseLabel = languageManager.getString("use_color_button"),
                        customColorCancelLabel = languageManager.getString("cancel"),
                        customColorHueLabel = languageManager.getString("hue_label"),
                        customColorSaturationLabel = languageManager.getString("saturation_label"),
                        customColorBrightnessLabel = languageManager.getString("brightness_label")
                    )
                }
                StyleMenuItem(
                    Icons.Filled.FormatColorReset,
                    languageManager.getString("rich_color_reset"),
                    checked = false
                ) {
                    state.removeSpanStyle(SpanStyle(color = current.color))
                    colorMenuOpen = false
                }
            }
        }
    }
}

/** One row of a format dropdown: its own icon, its label, and a check when the style is on. */
@Composable
private fun StyleMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = if (checked) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null,
        onClick = onClick
    )
}

/** One small toggle in the bar: pressed reads as filled. */
@Composable
private fun FormatToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.padding(5.dp).size(18.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Resolves [noteId]'s attachment rows into display items and wires add/remove to
 *  [NotesStateManager], including staging a newly-picked photo into this app's own files dir before
 *  recording it. Split out from [NoteEditorCard] so that composable's own signature stays scannable. */
@Composable
private fun NoteAttachmentsHost(noteId: Long, stateManager: NotesStateManager) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val allEntities by stateManager.observeAttachments(noteId).collectAsStateWithLifecycle(initialValue = emptyList())
    // Inline media (journal photos/voice notes) live in the note body — the strip is the home of
    // everything else, so showing them here would double every item.
    val entities = remember(allEntities) {
        allEntities.filterNot { it.source == AttachmentSource.INLINE_PHOTO || it.source == AttachmentSource.VOICE }
    }
    // An empty strip is pure chrome on a journal-style note — it appears once something actually
    // lands in it (a Vision scan capture keeps filing here regardless of this UI).
    if (entities.isEmpty()) return
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
    // Same rule as the saved-note strip: it earns its screen space only once it holds something.
    // A draft's photos come in through the body's own "+" anyway.
    if (pendingAttachments.isEmpty()) return
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
