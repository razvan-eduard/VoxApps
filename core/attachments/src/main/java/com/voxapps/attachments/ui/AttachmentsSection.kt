package com.voxapps.attachments.ui

import android.graphics.BitmapFactory
import android.net.Uri
import com.voxapps.attachments.AttachmentSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voxapps.design.SpeedDialAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/** One resolved attachment for display — each app maps its own [com.voxapps.attachments.AttachmentEntity]
 *  rows to these (via its own FileProvider authority), so this UI component never has to know about
 *  file storage or entity ids directly. */
data class AttachmentUiItem(
    val id: Long,
    val uri: Uri,
    val removable: Boolean,
    // Precomputed by the caller (e.g. "2/3") from AttachmentEntity.groupId/groupOrder — null for a
    // group of one, which renders identically to before this field existed. Kept as a caller-supplied
    // label rather than raw groupId/groupOrder fields so this component stays presentational and
    // doesn't need to know how grouping is computed or how it should be sorted within a group.
    val groupLabel: String? = null,
    // The raw AttachmentEntity.groupId, distinct from the display-only groupLabel above — lets this
    // module (and rememberVisionCaptureLauncher) compute a photo's group siblings for carousel
    // navigation / live capture-session tracking without parsing a formatted label string.
    val groupKey: String? = null,
    // The raw AttachmentEntity.source for a grouped item — when it's AttachmentSource.STITCHED, the
    // zoom view only offers whole-group delete, never per-photo: a stitch group is conceptually one
    // document split across shots (see com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_STITCH), unlike a
    // same-groupId gallery multi-select (source stays MANUAL), where each photo is still independent
    // and per-photo delete stays available. Meaningless for an ungrouped item.
    val groupSource: String? = null
)

/** Text/callback bundle for the zoom view's "delete this whole burst" action — only rendered when
 *  supplied, and only reachable for a photo that's part of a 2+ group (a lone photo has nothing
 *  distinct to offer beyond the regular per-photo [AttachmentsSection.onRemove]). Bundled into one
 *  param (rather than several independently-optional ones) so there's no way to wire the callback
 *  without also supplying the confirm-dialog copy it needs. */
data class GroupDeleteConfig(
    val onDeleteGroup: (groupKey: String) -> Unit,
    val confirmTitle: String,
    val confirmMessage: String,
    val confirmLabel: String,
    val cancelLabel: String
)

/**
 * Collapsible per-record attachments card: a thumbnail strip + an "add" tile, with tap-to-zoom.
 * Shared across Notes/Expenses/Calendar so every record's photo section behaves and looks the same.
 *
 * Renders whenever there's something to show *or* something that could be added — a record with zero
 * attachments still shows the card (just the "add" tile) so the feature is discoverable, unless
 * [canAdd] is also false (e.g. at the per-record cap with nothing attached yet, which shouldn't
 * normally happen). A non-removable item (the original scan photo, when [AttachmentUiItem.removable]
 * is false) always sorts first.
 */
@Composable
fun AttachmentsSection(
    title: String,
    items: List<AttachmentUiItem>,
    canAdd: Boolean,
    onPickFromGallery: () -> Unit,
    // One row per capture mode the caller wants offered (e.g. Single + Stitch, or Single + Batch —
    // see com.voxapps.ipc.VoxOcrRequest.captureMode) — replaces a single "Take photo" action since a
    // capture can no longer be triggered without first choosing a mode.
    captureActions: List<SpeedDialAction>,
    galleryLabel: String,
    cancelLabel: String,
    // Vision-powered capture modes, rendered inside a labeled outline so the user can tell at a
    // glance which options come from Vox Vision and which are plain phone features. Empty (the
    // default, and what callers pass when Vision isn't installed) hides the whole group.
    visionActions: List<SpeedDialAction> = emptyList(),
    visionLabel: String = "Vision",
    onRemove: (AttachmentUiItem) -> Unit,
    modifier: Modifier = Modifier,
    // Rescan/retry now lives exclusively in the zoom-view (see below), not the thumbnail strip — a
    // single icon that, for a grouped photo, always means "refresh the whole group's read," never a
    // single page in isolation (a group is one physical multi-page document). Null (the default)
    // hides it entirely — Calendar/Notes have no rescan concept and never wire this.
    onRescan: ((AttachmentUiItem) -> Unit)? = null,
    groupDelete: GroupDeleteConfig? = null
) {
    if (items.isEmpty() && !canAdd) return

    var showChooser by remember { mutableStateOf(false) }

    // Defaults expanded only when there's already something to show — an empty section (nothing
    // attached yet) starts collapsed rather than showing an otherwise-empty-looking strip. Keyed on
    // the presence/absence of items (not unkeyed) because the caller's list commonly starts empty
    // for one frame before its DB flow emits — an unkeyed remember would lock in "collapsed" from
    // that first frame and never reopen once the real (non-empty) list arrives.
    var expanded by remember(items.isNotEmpty()) { mutableStateOf(items.isNotEmpty()) }
    var zoomedItem by remember { mutableStateOf<AttachmentUiItem?>(null) }
    val ordered = remember(items) { items.sortedBy { it.removable } }
    // Consecutive same-groupKey runs (a group's members are always inserted/queried together, so they
    // stay adjacent through the removable-only sort above) — rendered as one chunk so a single green
    // outline can wrap the whole batch/stitch group instead of each thumbnail getting its own.
    val chunks = remember(ordered) {
        val result = mutableListOf<List<AttachmentUiItem>>()
        var current = mutableListOf<AttachmentUiItem>()
        for (item in ordered) {
            if (current.isEmpty() || (item.groupKey != null && item.groupKey == current.last().groupKey)) {
                current.add(item)
            } else {
                result.add(current)
                current = mutableListOf(item)
            }
        }
        if (current.isNotEmpty()) result.add(current)
        result
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Photo,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            if (expanded) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chunks, key = { it.first().id }) { chunk ->
                        // One outline around the whole run for a real group (2+ members) — a lone
                        // photo (including a "group of one") gets no border, matching the pre-chunking
                        // look exactly.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = if (chunk.size > 1) {
                                Modifier
                                    .border(2.dp, Color(0xFF2E7D32), RoundedCornerShape(10.dp))
                                    .padding(3.dp)
                            } else {
                                Modifier
                            }
                        ) {
                            chunk.forEach { item ->
                                Box(modifier = Modifier.size(88.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                    ) {
                                        // Zoom/pan disabled here (maxZoomFactor = 1f) — a thumbnail this
                                        // small has nothing useful to pan/zoom into; tapping it opens the
                                        // full-size, fully zoomable view in the dialog below instead.
                                        // Modifier.zoomable() (used internally by ZoomableAsyncImage) consumes
                                        // all gestures, so an outer Modifier.clickable never sees the tap —
                                        // this composable's own onClick param is the supported hook instead.
                                        ZoomableAsyncImage(
                                            model = item.uri,
                                            contentDescription = null,
                                            state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 1f))),
                                            onClick = { zoomedItem = item },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    if (item.removable) {
                                        IconButton(
                                            onClick = { onRemove(item) },
                                            modifier = Modifier
                                                .size(22.dp)
                                                .align(Alignment.TopEnd)
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                                    RoundedCornerShape(50)
                                                )
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Remove",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    if (item.groupLabel != null) {
                                        Text(
                                            text = item.groupLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(3.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (canAdd) {
                        item {
                            Box(
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { showChooser = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "Add attachment")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text(title) },
            text = {
                Column {
                    TextButton(onClick = { showChooser = false; onPickFromGallery() }) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(galleryLabel)
                    }
                    if (visionActions.isNotEmpty()) {
                        // The Vision group: a small outline whose top border the "Vision" label cuts
                        // through (same labeled-frame treatment as the remap editor's fields) — the
                        // visual fence between Vision features and the phone's own.
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                visionActions.forEach { action ->
                                    TextButton(onClick = { showChooser = false; action.onClick() }) {
                                        Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(action.label)
                                    }
                                }
                            }
                            Text(
                                visionLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .offset(x = 12.dp, y = (-8).dp)
                                    .background(AlertDialogDefaults.containerColor)
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }
                    captureActions.forEach { action ->
                        TextButton(onClick = { showChooser = false; action.onClick() }) {
                            Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(action.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChooser = false }) { Text(cancelLabel) }
            }
        )
    }

    val current = zoomedItem
    if (current != null) {
        val context = LocalContext.current
        // A lone photo's own groupKey still resolves to a one-element list here — deliberately not
        // special-cased, since "1 sibling" and "no group at all" already render identically (no
        // header/arrows/group-delete rendered unless there are 2+).
        val groupSiblings = remember(items, current.groupKey) {
            current.groupKey?.let { gk -> items.filter { it.groupKey == gk } } ?: listOf(current)
        }
        val isGrouped = groupSiblings.size > 1
        val indexInGroup = remember(groupSiblings, current.id) {
            groupSiblings.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        }
        var showDeleteGroupConfirm by remember(current.groupKey) { mutableStateOf(false) }

        // The dialog's aspect ratio follows the photo's own dimensions rather than a fixed square,
        // so portrait/landscape photos aren't padded with empty letterboxing — decoded with
        // inJustDecodeBounds so this never allocates the full bitmap just to measure it.
        var aspectRatio by remember(current.uri) { mutableStateOf<Float?>(null) }
        LaunchedEffect(current.uri) {
            aspectRatio = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(current.uri)?.use { stream ->
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, options)
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            options.outWidth.toFloat() / options.outHeight
                        } else null
                    }
                }.getOrNull()
            }
        }

        Dialog(
            onDismissRequest = { zoomedItem = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                if (isGrouped) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Photo ${indexInGroup + 1} of ${groupSiblings.size}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (onRescan != null) {
                            IconButton(onClick = { onRescan(current) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Rescan this group")
                            }
                        }
                    }
                }
                // BoxWithConstraints (not just fillMaxWidth+aspectRatio) so a tall/narrow photo's
                // computed height is also capped against the available height — otherwise a portrait
                // screenshot can make the card taller than the screen, pushing the action buttons
                // (aligned to the card's own top corners) outside the touchable/visible area entirely.
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val maxCardWidth = maxWidth * 0.9f
                    val maxCardHeight = maxHeight * 0.9f
                    val ratio = aspectRatio ?: 1f
                    var cardWidth = maxCardWidth
                    var cardHeight = cardWidth / ratio
                    if (cardHeight > maxCardHeight) {
                        cardHeight = maxCardHeight
                        cardWidth = cardHeight * ratio
                    }
                    Box(
                        modifier = Modifier
                            .width(cardWidth)
                            .height(cardHeight)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        // No tap-to-dismiss on the image itself here — it conflicts with this state's
                        // pan/zoom gestures (a tap can get eaten by the zoom gesture detector). The
                        // explicit close button below is the only way to dismiss besides system back.
                        ZoomableAsyncImage(
                            model = current.uri,
                            contentDescription = null,
                            state = rememberZoomableImageState(),
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isGrouped) {
                            if (indexInGroup > 0) {
                                IconButton(
                                    onClick = { zoomedItem = groupSiblings[indexInGroup - 1] },
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(4.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                ) {
                                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous photo")
                                }
                            }
                            if (indexInGroup < groupSiblings.lastIndex) {
                                IconButton(
                                    onClick = { zoomedItem = groupSiblings[indexInGroup + 1] },
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(4.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                ) {
                                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next photo")
                                }
                            }
                        }
                        // A stitch group is conceptually one document split across shots — only
                        // whole-group delete makes sense for it, never picking off one page (see
                        // AttachmentUiItem.groupSource's own doc comment). A gallery multi-select
                        // group (source stays MANUAL) keeps per-photo delete, same as today.
                        val isStitchedGroup = isGrouped && current.groupSource == AttachmentSource.STITCHED
                        Row(
                            modifier = Modifier.padding(8.dp).align(Alignment.TopEnd),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (current.removable && !isStitchedGroup) {
                                IconButton(
                                    onClick = { onRemove(current); zoomedItem = null },
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete photo")
                                }
                            }
                            if (isGrouped && groupDelete != null) {
                                IconButton(
                                    onClick = { showDeleteGroupConfirm = true },
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                ) {
                                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete all photos in this group")
                                }
                            }
                            if (!isGrouped && onRescan != null) {
                                IconButton(
                                    onClick = { onRescan(current) },
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                                }
                            }
                            IconButton(
                                onClick = { zoomedItem = null },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                    .border(1.dp, Color.Red, CircleShape)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }

        if (showDeleteGroupConfirm && groupDelete != null) {
            AlertDialog(
                onDismissRequest = { showDeleteGroupConfirm = false },
                title = { Text(groupDelete.confirmTitle) },
                text = { Text(groupDelete.confirmMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteGroupConfirm = false
                        current.groupKey?.let(groupDelete.onDeleteGroup)
                        zoomedItem = null
                    }) { Text(groupDelete.confirmLabel) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteGroupConfirm = false }) { Text(groupDelete.cancelLabel) }
                }
            )
        }
    }
}
