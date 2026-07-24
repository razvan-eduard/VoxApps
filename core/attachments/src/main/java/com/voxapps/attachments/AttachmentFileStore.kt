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

    /** Copies [sourceUri]'s content into `filesDir/<dirName>/att_<uuid>.jpg`, returning the new
     *  filename (not a path/URI — same convention as every other Vox image-attachment field). */
    fun stage(context: Context, sourceUri: Uri, dirName: String): String? = try {
        val dir = File(context.filesDir, dirName).apply { mkdirs() }
        val fileName = "att_${UUID.randomUUID()}.jpg"
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            File(dir, fileName).outputStream().use { output -> input.copyTo(output) }
        }
        fileName
    } catch (e: Exception) {
        null
    }

    fun file(context: Context, dirName: String, fileName: String): File =
        File(File(context.filesDir, dirName), fileName)

    /** Grants a `content://` URI for [fileName] via the caller's own FileProvider [authority]. */
    fun uriFor(context: Context, authority: String, dirName: String, fileName: String): Uri =
        FileProvider.getUriForFile(context, authority, file(context, dirName, fileName))

    fun delete(context: Context, dirName: String, fileName: String) {
        file(context, dirName, fileName).delete()
    }
}
