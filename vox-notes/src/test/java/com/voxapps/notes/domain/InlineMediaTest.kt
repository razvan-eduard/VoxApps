package com.voxapps.notes.domain

import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMediaTest {

    private fun row(fileName: String, source: String) =
        AttachmentEntity(id = 0, recordType = "note", recordId = 1, fileName = fileName, source = source, createdAt = 0)

    @Test
    fun `text-only html is a single text block`() {
        val blocks = InlineMedia.splitBlocks("<p>hello <b>world</b></p>")
        assertEquals(listOf<NoteBlock>(NoteBlock.Text("<p>hello <b>world</b></p>")), blocks)
    }

    @Test
    fun `built markers round-trip through split`() {
        val html = "<p>before</p>" +
            InlineMedia.buildPhotoMarker("att_1.jpg", 140, 105) +
            "<p>middle</p>" +
            InlineMedia.buildVoiceMarker("voice_2.m4a", 65_000) +
            "<p>after</p>"
        val blocks = InlineMedia.splitBlocks(html)
        assertEquals(
            listOf(
                NoteBlock.Text("<p>before</p>"),
                NoteBlock.Photo("att_1.jpg", 140, 105),
                NoteBlock.Text("<p>middle</p>"),
                NoteBlock.Voice("voice_2.m4a", "🎤 1:05"),
                NoteBlock.Text("<p>after</p>")
            ),
            blocks
        )
    }

    @Test
    fun `join is the inverse of split`() {
        val blocks = listOf(
            NoteBlock.Text("<p>a</p>"),
            NoteBlock.Photo("att_1.jpg", 140, 90),
            NoteBlock.Voice("voice_2.m4a", "🎤 0:12"),
            NoteBlock.Text("<p>b</p>")
        )
        assertEquals(blocks, InlineMedia.splitBlocks(InlineMedia.joinBlocks(blocks)))
    }

    @Test
    fun `media-only html still yields a text block to type into`() {
        val blocks = InlineMedia.splitBlocks(InlineMedia.buildPhotoMarker("att_1.jpg", 140, 140))
        assertEquals(NoteBlock.Photo("att_1.jpg", 140, 140), blocks[0])
        assertTrue(blocks.any { it is NoteBlock.Text })
    }

    @Test
    fun `marker inserted mid-paragraph falls out as its own block with both text sides kept`() {
        // What insertHtmlAfterSelection produces: the marker lands inside the paragraph being edited.
        val html = """<p>start <img src="att://att_9.jpg" width="140" height="140" alt="photo"> end</p>"""
        val blocks = InlineMedia.splitBlocks(html)
        assertEquals(3, blocks.size)
        assertTrue((blocks[0] as NoteBlock.Text).html.contains("start"))
        assertEquals(NoteBlock.Photo("att_9.jpg", 140, 140), blocks[1])
        assertTrue((blocks[2] as NoteBlock.Text).html.contains("end"))
    }

    @Test
    fun `attribute order and single quotes are tolerated`() {
        val html = """<p><img width='120' alt='x' src='att://f.jpg' height='80'></p><p><a class='l' href='voice://v.m4a'>🎤 0:07</a></p>"""
        val blocks = InlineMedia.splitBlocks(html)
        assertEquals(NoteBlock.Photo("f.jpg", 120, 80), blocks[0])
        assertEquals(NoteBlock.Voice("v.m4a", "🎤 0:07"), blocks[1])
    }

    @Test
    fun `plain http links and ordinary images are not media blocks`() {
        val html = """<p><a href="https://x.org">site</a> <img src="https://x.org/i.png"></p>"""
        assertEquals(listOf<NoteBlock>(NoteBlock.Text(html)), InlineMedia.splitBlocks(html))
        assertFalse(InlineMedia.hasMedia(html))
        assertTrue(InlineMedia.mediaRefs(html).isEmpty())
    }

    @Test
    fun `duration formats like a clock`() {
        assertEquals("0:00", InlineMedia.formatDuration(0))
        assertEquals("0:59", InlineMedia.formatDuration(59_999))
        assertEquals("1:05", InlineMedia.formatDuration(65_000))
        assertEquals("59:59", InlineMedia.formatDuration(3_599_000))
    }

    @Test
    fun `mediaRefs come back in document order with their sources`() {
        val html = InlineMedia.buildVoiceMarker("v.m4a", 1000) + "<p>t</p>" + InlineMedia.buildPhotoMarker("p.jpg", 140, 140)
        assertEquals(
            listOf(
                InlineMediaRef("v.m4a", AttachmentSource.VOICE),
                InlineMediaRef("p.jpg", AttachmentSource.INLINE_PHOTO)
            ),
            InlineMedia.mediaRefs(html)
        )
    }

    @Test
    fun `diff inserts new markers and deletes stale inline rows only`() {
        val refs = listOf(
            InlineMediaRef("keep.jpg", AttachmentSource.INLINE_PHOTO),
            InlineMediaRef("new.m4a", AttachmentSource.VOICE)
        )
        val rows = listOf(
            row("keep.jpg", AttachmentSource.INLINE_PHOTO),
            row("stale.jpg", AttachmentSource.INLINE_PHOTO),
            row("strip.jpg", AttachmentSource.MANUAL),
            row("scan.jpg", AttachmentSource.SCANNED)
        )
        val (toInsert, toDelete) = InlineMedia.diff(refs, rows)
        assertEquals(listOf(InlineMediaRef("new.m4a", AttachmentSource.VOICE)), toInsert)
        assertEquals(listOf("stale.jpg"), toDelete.map { it.fileName })
    }

    @Test
    fun `thumb dimensions follow aspect within clamps`() {
        assertEquals(140 to 105, InlineMedia.thumbDimensions(4000, 3000))
        assertEquals(140 to 220, InlineMedia.thumbDimensions(1000, 4000))
        assertEquals(140 to 80, InlineMedia.thumbDimensions(4000, 1000))
        assertEquals(140 to 140, InlineMedia.thumbDimensions(0, 0))
    }
}
