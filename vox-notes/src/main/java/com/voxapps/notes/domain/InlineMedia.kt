package com.voxapps.notes.domain

import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource

/**
 * A note body split into journal blocks: rich-text runs interleaved with media rows. The split is
 * derived from [markers in the stored HTML][splitBlocks] and never persisted — `textHtml` (with one
 * marker paragraph per media item) stays the single source of truth, and the attachment rows are
 * reconciled from it on save ([diff]).
 */
sealed interface NoteBlock {
    data class Text(val html: String) : NoteBlock
    data class Photo(val fileName: String, val width: Int, val height: Int) : NoteBlock
    data class Voice(val fileName: String, val durationLabel: String) : NoteBlock
}

/** One media reference found in a note's HTML — what [InlineMedia.diff] reconciles rows against. */
data class InlineMediaRef(val fileName: String, val source: String)

object InlineMedia {

    /** URL schemes that mark a note-body media item; anything else in an img/a is left alone. */
    private const val PHOTO_SCHEME = "att://"
    private const val VOICE_SCHEME = "voice://"

    /** What a media item contributes to the plain [text][com.voxapps.notes.data.Note.text]. These
     *  are load-bearing, not cosmetic: three code paths delete/drop a note whose plain text is
     *  empty, so a photo-only journal entry must still produce non-empty text. */
    const val PHOTO_PLACEHOLDER = "📷"
    const val VOICE_PLACEHOLDER_PREFIX = "🎤"

    // Attribute order and quoting vary between what this code writes and what the editor's own
    // toHtml() gives back — both patterns match on the scheme'd attribute alone and read the rest
    // out of the whole tag.
    private val photoMarker = Regex("""<img\b[^>]*src\s*=\s*["']$PHOTO_SCHEME([^"']+)["'][^>]*>""")
    private val voiceMarker = Regex("""<a\b[^>]*href\s*=\s*["']$VOICE_SCHEME([^"']+)["'][^>]*>([^<]*)</a>""")
    private val attr = { name: String -> Regex("""\b$name\s*=\s*["']([^"']+)["']""") }

    // The paragraph shell a lone marker leaves behind once the marker itself is cut out.
    private val emptyParagraph = Regex("""<p[^>]*>(\s|<br\s*/?>|&nbsp;)*</p>""")

    fun buildPhotoMarker(fileName: String, width: Int, height: Int): String =
        """<p><img src="$PHOTO_SCHEME$fileName" width="$width" height="$height" alt="photo"></p>"""

    fun buildVoiceMarker(fileName: String, durationMs: Long): String =
        """<p><a href="$VOICE_SCHEME$fileName">$VOICE_PLACEHOLDER_PREFIX ${formatDuration(durationMs)}</a></p>"""

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    fun hasMedia(html: String?): Boolean =
        html != null && (photoMarker.containsMatchIn(html) || voiceMarker.containsMatchIn(html))

    /**
     * Splits [html] into text runs and media blocks. Media markers are recognized wherever they
     * sit — mid-paragraph right after an editor insert, or in the canonical own-paragraph form —
     * and the paragraph shells they leave behind are dropped so a cut never strands an empty `<p>`.
     * Always returns at least one [NoteBlock.Text] (possibly empty) so an editor has a place to type.
     */
    fun splitBlocks(html: String): List<NoteBlock> {
        data class Hit(val range: IntRange, val block: NoteBlock)

        val hits = buildList {
            photoMarker.findAll(html).forEach { m ->
                val tag = m.value
                val width = attr("width").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_THUMB_WIDTH
                val height = attr("height").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_THUMB_WIDTH
                add(Hit(m.range, NoteBlock.Photo(m.groupValues[1], width, height)))
            }
            voiceMarker.findAll(html).forEach { m ->
                add(Hit(m.range, NoteBlock.Voice(m.groupValues[1], m.groupValues[2].trim())))
            }
        }.sortedBy { it.range.first }

        if (hits.isEmpty()) return listOf(NoteBlock.Text(html))

        val blocks = mutableListOf<NoteBlock>()
        var cursor = 0
        for (hit in hits) {
            val before = html.substring(cursor, hit.range.first)
            addTextBlock(blocks, before)
            blocks.add(hit.block)
            cursor = hit.range.last + 1
        }
        addTextBlock(blocks, html.substring(cursor))
        if (blocks.none { it is NoteBlock.Text }) blocks.add(NoteBlock.Text(""))
        return blocks
    }

    private fun addTextBlock(blocks: MutableList<NoteBlock>, rawHtml: String) {
        val cleaned = rawHtml.replace(emptyParagraph, "")
            // A marker cut out of the middle of a paragraph leaves an open shell on one side and a
            // closing one on the other; reclose/reopen them so each side stays valid HTML.
            .let {
                var s = it
                if (s.trimEnd().endsWith("<p>", ignoreCase = true)) s = s.trimEnd().removeSuffix("<p>")
                if (s.trimStart().startsWith("</p>", ignoreCase = true)) s = s.trimStart().removePrefix("</p>")
                s
            }
            .trim()
        if (cleaned.isNotEmpty()) blocks.add(NoteBlock.Text(cleaned))
    }

    /** Inverse of [splitBlocks]: canonical marker paragraphs between the text runs. */
    fun joinBlocks(blocks: List<NoteBlock>): String = blocks.joinToString(separator = "") { block ->
        when (block) {
            is NoteBlock.Text -> block.html
            is NoteBlock.Photo -> buildPhotoMarker(block.fileName, block.width, block.height)
            is NoteBlock.Voice ->
                """<p><a href="$VOICE_SCHEME${block.fileName}">${block.durationLabel}</a></p>"""
        }
    }

    /** Every media reference in [html], in document order, with the attachment source it maps to. */
    fun mediaRefs(html: String?): List<InlineMediaRef> {
        if (html == null) return emptyList()
        return buildList {
            photoMarker.findAll(html).forEach { add(it.range.first to InlineMediaRef(it.groupValues[1], AttachmentSource.INLINE_PHOTO)) }
            voiceMarker.findAll(html).forEach { add(it.range.first to InlineMediaRef(it.groupValues[1], AttachmentSource.VOICE)) }
        }.sortedBy { it.first }.map { it.second }
    }

    /**
     * Reconciles marker truth against stored rows: [first] = refs with no row yet (insert),
     * [second] = rows whose marker is gone (delete row + file). Rows from the attachments strip
     * (manual/scanned/stitched) are never touched — only the two inline sources are considered.
     */
    fun diff(refs: List<InlineMediaRef>, rows: List<AttachmentEntity>): Pair<List<InlineMediaRef>, List<AttachmentEntity>> {
        val inlineRows = rows.filter { it.source == AttachmentSource.INLINE_PHOTO || it.source == AttachmentSource.VOICE }
        val refNames = refs.map { it.fileName }.toSet()
        val rowNames = inlineRows.map { it.fileName }.toSet()
        val toInsert = refs.filter { it.fileName !in rowNames }.distinctBy { it.fileName }
        val toDelete = inlineRows.filter { it.fileName !in refNames }
        return toInsert to toDelete
    }

    const val DEFAULT_THUMB_WIDTH = 140

    /** Display size for an inline thumbnail: fixed width, height following the photo's own aspect
     *  ratio, clamped so an extreme panorama or receipt strip stays a sane row. */
    fun thumbDimensions(realWidth: Int, realHeight: Int): Pair<Int, Int> {
        if (realWidth <= 0 || realHeight <= 0) return DEFAULT_THUMB_WIDTH to DEFAULT_THUMB_WIDTH
        val height = (DEFAULT_THUMB_WIDTH.toLong() * realHeight / realWidth).toInt().coerceIn(80, 220)
        return DEFAULT_THUMB_WIDTH to height
    }
}
