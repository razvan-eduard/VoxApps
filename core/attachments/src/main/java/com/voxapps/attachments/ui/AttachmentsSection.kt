package com.voxapps.attachments.ui

import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Photo
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
    val removable: Boolean
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
    onTakePhoto: () -> Unit,
    galleryLabel: String,
    cameraLabel: String,
    cancelLabel: String,
    onRemove: (AttachmentUiItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty() && !canAdd) return

    var showChooser by remember { mutableStateOf(false) }

    // Defaults expanded only when there's already something to show — an empty section (nothing
    // attached yet) starts collapsed rather than showing an otherwise-empty-looking strip. Keyed on
    // the presence/absence of items (not unkeyed) because the caller's list commonly starts empty
    // for one frame before its DB flow emits — an unkeyed remember would lock in "collapsed" from
    // that first frame and never reopen once the real (non-empty) list arrives.
    var expanded by remember(items.isNotEmpty()) { mutableStateOf(items.isNotEmpty()) }
    var zoomedUri by remember { mutableStateOf<Uri?>(null) }
    val ordered = remember(items) { items.sortedBy { it.removable } }

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
                    items(ordered, key = { it.id }) { item ->
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
                                    onClick = { zoomedUri = item.uri },
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
                    TextButton(onClick = { showChooser = false; onPickFromGallery() }) { Text(galleryLabel) }
                    TextButton(onClick = { showChooser = false; onTakePhoto() }) { Text(cameraLabel) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChooser = false }) { Text(cancelLabel) }
            }
        )
    }

    val toZoom = zoomedUri
    if (toZoom != null) {
        val context = LocalContext.current
        // The dialog's aspect ratio follows the photo's own dimensions rather than a fixed square,
        // so portrait/landscape photos aren't padded with empty letterboxing — decoded with
        // inJustDecodeBounds so this never allocates the full bitmap just to measure it.
        var aspectRatio by remember(toZoom) { mutableStateOf<Float?>(null) }
        LaunchedEffect(toZoom) {
            aspectRatio = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(toZoom)?.use { stream ->
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
            onDismissRequest = { zoomedUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // BoxWithConstraints (not just fillMaxWidth+aspectRatio) so a tall/narrow photo's
            // computed height is also capped against the available height — otherwise a portrait
            // screenshot can make the card taller than the screen, pushing the close button (aligned
            // to the card's own top-right corner) outside the touchable/visible area entirely.
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(2.dp),
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
                        model = toZoom,
                        contentDescription = null,
                        state = rememberZoomableImageState(),
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = { zoomedUri = null },
                        modifier = Modifier
                            .padding(8.dp)
                            .align(Alignment.TopEnd)
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
