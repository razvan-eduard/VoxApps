package com.voxapps.attachments

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Copies/resolves/deletes attachment files under a caller-supplied `filesDir/<dirName>/` — this
 * module never declares its own `<provider>` (every app already has its own FileProvider authority
 * and `res/xml/file_paths.xml`), so every function here takes that app's authority/dir as a plain
 * parameter instead of assuming one.
 */
object AttachmentFileStore {

    /** Copies [sourceUri]'s content into `filesDir/<dirName>/att_<uuid>.<ext>`, returning the new
     *  filename (not a path/URI — same convention as every other Vox image-attachment field). */
    fun stage(context: Context, sourceUri: Uri, dirName: String): String? = try {
        val dir = File(context.filesDir, dirName).apply { mkdirs() }
        val fileName = "att_${UUID.randomUUID()}.${extensionFor(context, sourceUri)}"
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            File(dir, fileName).outputStream().use { output -> input.copyTo(output) }
        }
        fileName
    } catch (e: Exception) {
        null
    }

    /** The staged copy keeps the source's real type. There is no mime column anywhere — the
     *  extension IS the suite's type record (voice_<uuid>.m4a set the precedent), and it's what
     *  FileProvider.getType, ACTION_VIEW targets and the strip's own kind dispatch all read. */
    private fun extensionFor(context: Context, uri: Uri): String {
        val fromMime = context.contentResolver.getType(uri)
            ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val fromPath = uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        val ext = (fromMime ?: fromPath ?: "jpg").lowercase()
        return ext.takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
    }

    fun file(context: Context, dirName: String, fileName: String): File =
        File(File(context.filesDir, dirName), fileName)

    /** Grants a `content://` URI for [fileName] via the caller's own FileProvider [authority]. */
    fun uriFor(context: Context, authority: String, dirName: String, fileName: String): Uri =
        FileProvider.getUriForFile(context, authority, file(context, dirName, fileName))

    fun delete(context: Context, dirName: String, fileName: String) {
        file(context, dirName, fileName).delete()
        // Harmless no-op for every caller that never creates one (calendar/notes attachments have no
        // such convention) — only vox-expenses' rescan/retry-with-a-different-photo features ever
        // stage OCR text as a same-named .txt sibling next to an attachment file. Deleting the
        // attachment should take that with it rather than leaving it permanently orphaned.
        File(file(context, dirName, fileName).parentFile, fileName.substringBeforeLast('.') + ".txt").delete()
    }
}
