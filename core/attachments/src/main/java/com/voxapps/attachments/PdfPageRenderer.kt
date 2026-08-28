package com.voxapps.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.voxapps.logging.Logger
import java.io.File
import java.util.UUID

/**
 * Renders a staged PDF's pages into staged JPEG images — the bridge between "the user picked a PDF"
 * and every pipeline in the suite that only understands bitmaps (OCR, the attachment strip, the
 * multimodal LLM copy). Deliberately file-in/files-out over the same `filesDir/<dirName>/`
 * convention as [AttachmentFileStore], so a rendered page is indistinguishable from any other staged
 * image to everything downstream.
 */
object PdfPageRenderer {

    private const val TAG = "PdfPageRenderer"

    /** Pages past this cap are skipped: it bounds render time, per-page URI grants and the OCR
     *  reply broadcast for pathological documents; receipts and invoices fit comfortably. */
    const val MAX_PAGES = 12

    // PDF geometry is in points (1/72 inch); ~300dpi is where OCR stops gaining from more pixels.
    private const val TARGET_SCALE = 300f / 72f

    // Small enough that the OCR detection stage's input tensor stays modest (a full-bleed render is
    // never downscaled by the engine's own 4000px clamp, so THIS is the only thing bounding that
    // tensor), large enough to out-resolve any camera capture on crisp vector text: ~187dpi for A4.
    // Measured the hard way — 3000px renders fed sequentially drove a 2.5GB device into swap-thrash
    // watchdog reboots when the caller's own UI held page bitmaps at the same time.
    private const val MAX_LONG_EDGE_PX = 2200

    /** Renders `filesDir/<dirName>/<pdfFileName>` into `att_<uuid>.jpg` siblings, one per page in
     *  page order, and returns their filenames. Null when the document can't be opened or no page
     *  renders (corrupt or password-protected input) — any pages already written are cleaned up. */
    fun renderToStagedJpegs(
        context: Context,
        dirName: String,
        pdfFileName: String,
        maxPages: Int = MAX_PAGES
    ): List<String>? {
        val staged = mutableListOf<String>()
        return try {
            ParcelFileDescriptor.open(
                AttachmentFileStore.file(context, dirName, pdfFileName),
                ParcelFileDescriptor.MODE_READ_ONLY
            ).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount > maxPages) {
                        Logger.w(TAG, "PDF has ${renderer.pageCount} pages; rendering only the first $maxPages")
                    }
                    val dir = File(context.filesDir, dirName).apply { mkdirs() }
                    for (index in 0 until minOf(renderer.pageCount, maxPages)) {
                        renderer.openPage(index).use { page ->
                            val scale = minOf(TARGET_SCALE, MAX_LONG_EDGE_PX.toFloat() / maxOf(page.width, page.height))
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            // Pages render onto transparency; OCR and JPEG both need the paper white.
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val fileName = "att_${UUID.randomUUID()}.jpg"
                            try {
                                File(dir, fileName).outputStream().use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                            } finally {
                                // One page's pixels in memory at a time — a loop that left N page
                                // bitmaps to the GC is exactly what once killed the OCR batch path.
                                bitmap.recycle()
                            }
                            staged.add(fileName)
                        }
                    }
                }
            }
            staged.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Logger.e(TAG, "Could not render $pdfFileName", t)
            staged.forEach { AttachmentFileStore.file(context, dirName, it).delete() }
            null
        }
    }
}
