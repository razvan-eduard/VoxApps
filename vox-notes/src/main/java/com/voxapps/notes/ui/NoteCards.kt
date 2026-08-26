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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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

                // The note's text is rich from here on: the state owns the spans, the bar
                // above the field toggles them, and every change leaves as the pair the
                // buffer stores — the plain text everything else reads, and the HTML that
                // preserves what was styled.
                val richState = rememberRichTextState()
                LaunchedEffect(noteId) {
                    richState.setHtml(textHtml ?: plainTextAsHtml(text))
                }
                LaunchedEffect(richState) {
                    snapshotFlow { richState.annotatedString }
                        .collect { onContentChange(it.text, richState.toHtml()) }
                }
                RichFormatBar(richState)
                // The writing room the handle at the card's foot controls: dragged, it grows
                // by exactly the drag; tapped, it jumps to most of the screen and back to
                // whatever size it had. A minimum only — content taller than the room still
                // grows the card the way it always did.
                val editorMinHeight = if (editorMaxed) {
                    (LocalConfiguration.current.screenHeightDp * 0.55f).dp
                } else {
                    DEFAULT_EDITOR_MIN_HEIGHT + with(LocalDensity.current) { editorExtraHeightPx.toDp() }
                }
                Box(
                    modifier = if (fullscreen) {
                        Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
                    } else {
                        Modifier.fillMaxWidth().heightIn(min = editorMinHeight).padding(top = 4.dp)
                    }
                ) {
                    if (richState.annotatedString.text.isEmpty()) {
                        Text(
                            languageManager.getString("note_text"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicRichTextEditor(
                        state = richState,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                    )
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

/** The sizes the bar cycles through — named steps rather than a number field, because a note is
 *  styled by eye, not typeset. */
private val RICH_TEXT_SIZES = listOf(14.sp, 18.sp, 24.sp, 32.sp)

/** The families the bar cycles through: the generic names every renderer knows. */
private val RICH_TEXT_FONTS = listOf(FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace)

/** The ink choices. Not themed colors on purpose: a color put on words is content, and content
 *  keeps its color whatever theme the note is later read under. */
private val RICH_TEXT_COLORS = listOf(
    Color(0xFFD32F2F), Color(0xFFF57C00), Color(0xFF388E3C),
    Color(0xFF1976D2), Color(0xFF7B1FA2)
)

/**
 * The small format bar the editor carries: bold, italic, underline, strikethrough as toggles that
 * read their pressed state off the cursor's own style, then size, font and ink. Size and font
 * cycle through fixed steps; ink is one dot per color plus a reset. Everything acts through
 * [RichTextState.toggleSpanStyle]-family calls, on the selection when there is one and on what is
 * typed next when there is not.
 */
@Composable
private fun RichFormatBar(state: RichTextState) {
    val languageManager = LocalLanguageManager.current
    val current = state.currentSpanStyle
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp)
    ) {
        FormatToggle(
            icon = Icons.Filled.FormatBold,
            active = current.fontWeight == FontWeight.Bold,
            description = languageManager.getString("rich_bold")
        ) { state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
        FormatToggle(
            icon = Icons.Filled.FormatItalic,
            active = current.fontStyle == FontStyle.Italic,
            description = languageManager.getString("rich_italic")
        ) { state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
        FormatToggle(
            icon = Icons.Filled.FormatUnderlined,
            active = current.textDecoration?.contains(TextDecoration.Underline) == true,
            description = languageManager.getString("rich_underline")
        ) { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
        FormatToggle(
            icon = Icons.Filled.FormatStrikethrough,
            active = current.textDecoration?.contains(TextDecoration.LineThrough) == true,
            description = languageManager.getString("rich_strikethrough")
        ) { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }

        Spacer(modifier = Modifier.width(6.dp))

        // Lists are paragraph-level: the toggle takes the section the cursor sits in (or the
        // selected paragraphs) in and out of bullets or numbering, and the two exclude each other
        // — the library moves a numbered paragraph straight to bullets rather than stacking them.
        FormatToggle(
            icon = Icons.Filled.FormatListBulleted,
            active = state.isUnorderedList,
            description = languageManager.getString("rich_bullets")
        ) { state.toggleUnorderedList() }
        FormatToggle(
            icon = Icons.Filled.FormatListNumbered,
            active = state.isOrderedList,
            description = languageManager.getString("rich_numbering")
        ) { state.toggleOrderedList() }

        Spacer(modifier = Modifier.width(6.dp))

        // Size: the next step up from wherever the cursor sits, wrapping back to small.
        FormatToggle(
            icon = Icons.Filled.FormatSize,
            active = RICH_TEXT_SIZES.drop(1).any { it == current.fontSize },
            description = languageManager.getString("rich_size")
        ) {
            val index = RICH_TEXT_SIZES.indexOfFirst { it == current.fontSize }
            val next = RICH_TEXT_SIZES[(index + 1).mod(RICH_TEXT_SIZES.size)]
            state.addSpanStyle(SpanStyle(fontSize = next))
        }
        // Font: sans → serif → mono, the three names every renderer knows.
        FormatToggle(
            icon = Icons.Filled.TextFormat,
            active = current.fontFamily != null && current.fontFamily != FontFamily.SansSerif,
            description = languageManager.getString("rich_font")
        ) {
            val index = RICH_TEXT_FONTS.indexOfFirst { it == current.fontFamily }
            val next = RICH_TEXT_FONTS[(index + 1).mod(RICH_TEXT_FONTS.size)]
            state.addSpanStyle(SpanStyle(fontFamily = next))
        }

        Spacer(modifier = Modifier.width(6.dp))

        RICH_TEXT_COLORS.forEach { color ->
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (current.color == color) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                    .clickable { state.addSpanStyle(SpanStyle(color = color)) }
            )
        }
        IconButton(
            onClick = { state.removeSpanStyle(SpanStyle(color = current.color)) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Filled.FormatColorReset,
                contentDescription = languageManager.getString("rich_color_reset"),
                modifier = Modifier.size(18.dp)
            )
        }
    }
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
