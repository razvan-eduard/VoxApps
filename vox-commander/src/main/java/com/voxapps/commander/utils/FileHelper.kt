package com.voxapps.commander.utils

import com.voxapps.logging.Logger

import android.content.Context
import android.net.Uri
import com.voxapps.commander.data.remote.RemoteModelRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Helper class for file operations.
 * Handles copying URIs to internal storage and deleting model files.
 */
object FileHelper {

    /**
     * Copies a URI to internal storage.
     * @param context Application context
     * @param uri Source URI
     * @param targetName Target filename
     * @return Absolute path of the copied file, or null if failed
     */
    fun copyUriToInternal(context: Context, uri: Uri, targetName: String): String? {
        return try {
            // `use` on both, not close() after copyTo — copyTo is by far the likeliest call here to
            // throw (source unreadable mid-read, target volume full), and the old form leaked BOTH
            // descriptors when it did, since neither close() was ever reached.
            val file = File(context.getExternalFilesDir(null), targetName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            file.absolutePath
        } catch (e: Exception) {
            Logger.log("copyUriToInternal failed: ${e.message}", Strings.Tags.FILE_HELPER)
            null
        }
    }

    /**
     * Deletes a model file from external storage.
     * @param context Application context
     * @param modelId Model identifier
     * @param engineKey Engine key from models.json (e.g. "stt_whisper", "wake_vosk", "nlu_llm")
     */
    fun deleteModelFile(context: Context, modelId: String, engineKey: String) {
        val extension = RemoteModelRegistry.getExtension(engineKey)
        val fileName = if (extension.isBlank()) modelId else "$modelId$extension"
        val file = File(context.getExternalFilesDir(null), fileName)
        Logger.log("Deleting model file: engineKey=$engineKey, modelId=$modelId, fileName=$fileName, path=${file.absolutePath}, exists=${file.exists()}", Strings.Tags.FILE_HELPER)
        if (file.exists()) {
            if (file.isDirectory) {
                file.deleteRecursively()
                Logger.log("Deleted directory: $fileName", Strings.Tags.FILE_HELPER)
            } else {
                file.delete()
                Logger.log("Deleted model file: $fileName", Strings.Tags.FILE_HELPER)
            }
        } else {
            Logger.log("Model file does not exist: $fileName", Strings.Tags.FILE_HELPER)
        }
    }

    /**
     * Deletes a partial download file.
     * @param context Application context
     * @param fileName Filename to delete
     */
    fun deletePartialDownload(context: Context, fileName: String) {
        val downloadsDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, fileName)
        if (file.exists()) {
            file.delete()
        }
    }
}
