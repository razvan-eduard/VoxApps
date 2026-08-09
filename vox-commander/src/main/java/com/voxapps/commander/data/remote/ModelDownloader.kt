package com.voxapps.commander.data.remote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.state.AppStateManager
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Agnostic Model Downloader & File Manager.
 * Uses RemoteModelRegistry as the SSOT for extensions and keys.
 */
class ModelDownloader(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    companion object {
        private const val TAG = "ModelDownloader"
        private const val CLEANUP_TAG = "ModelCleanup"

        /**
         * If the extracted directory contains a single subdirectory (the archive's top-level
         * wrapper, e.g. Vosk's `vosk-model-.../` or Piper's `vits-piper-.../`), move its
         * contents up one level so the model files sit directly under [targetDir].
         *
         * IMPORTANT: uses renameTo (atomic same-fs move) and falls back to copyRecursively —
         * NOT File.copyTo, which is non-recursive for directories and would leave subdirectories
         * like `am/ conf/ graph/` empty (Vosk then fails with "Failed to create a model").
         *
         * Pure File logic (no Context) so it is unit-testable.
         */
        internal fun flattenNestedDir(targetDir: File) {
            val children = targetDir.listFiles() ?: return
            if (children.size == 1 && children[0].isDirectory) {
                val nested = children[0]
                Logger.log("Flattening nested directory: ${nested.name}", TAG)
                nested.listFiles()?.forEach { file ->
                    val dest = File(targetDir, file.name)
                    if (!file.renameTo(dest)) {
                        // Cross-filesystem or rename failure — recurse into subdirectories too.
                        file.copyRecursively(dest, overwrite = true)
                    }
                }
                nested.deleteRecursively()
            }
        }

        /** Depth cap for [resolveEntry]'s search. A real unpacked model nests two or three levels at
         *  most; this only has to be past that and short of following a directory symlink cycle. */
        private const val MAX_ENTRY_SEARCH_DEPTH = 10

        /**
         * Resolves what an engine's library actually needs to be handed, from the engine's own
         * [EntryPoint] declaration.
         *
         * This is the single answer to "where is the loadable artefact", used by both the validator
         * and the engine. They used to answer it separately and disagree: the validator looked for a
         * fixed `model.onnx` while Piper archives ship `<voice>.onnx`, so a correctly extracted voice
         * was judged incomplete and deleted on the next launch.
         *
         * Searching for the marker rather than trusting a fixed relative path also subsumes
         * [flattenNestedDir]: an archive that wraps its contents in one extra directory resolves to
         * that directory instead of needing the files moved on disk first.
         *
         * Pure File logic (no Context) so it is unit-testable.
         */
        internal fun resolveEntry(root: File, entry: EntryPoint): File? {
            if (!root.exists()) return null
            if (entry.self) return root.takeIf { it.isFile }

            val pattern = entry.match?.takeIf { it.isNotBlank() } ?: return null
            val matched = findMatch(root, globToRegex(pattern)) ?: return null

            val resolved = when (entry.target) {
                TARGET_DIR -> matched.parentFile
                else -> matched
            } ?: return null

            // `match` can arrive from a models.json served by a user-configured modelRepoBaseUrl, so
            // it is untrusted input describing a path. Confine the result to the model's own
            // directory — the same concern as zip-slip, one step later in the pipeline.
            val rootPath = root.canonicalPath
            val resolvedPath = resolved.canonicalPath
            if (resolvedPath != rootPath && !resolvedPath.startsWith(rootPath + File.separator)) {
                Logger.log("Entry point '$pattern' resolved outside $rootPath — refusing", TAG)
                return null
            }
            return resolved
        }

        /**
         * Breadth-first so a match directly under [root] wins over a deeper one — an archive that
         * happens to carry a stray copy in a subdirectory still resolves to the real layout.
         * Children are visited in name order so the result does not depend on filesystem ordering.
         */
        private fun findMatch(root: File, regex: Regex): File? {
            var frontier = listOf(root)
            var depth = 0
            while (frontier.isNotEmpty() && depth <= MAX_ENTRY_SEARCH_DEPTH) {
                val next = mutableListOf<File>()
                for (dir in frontier) {
                    val children = dir.listFiles()?.sortedBy { it.name } ?: continue
                    children.firstOrNull { regex.matches(it.name) }?.let { return it }
                    next += children.filter { it.isDirectory }
                }
                frontier = next
                depth++
            }
            return null
        }

        /** Supports `*` and `?`; everything else is matched literally. */
        private fun globToRegex(glob: String): Regex {
            val pattern = buildString {
                glob.forEach { c ->
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
            }
            return Regex(pattern, RegexOption.IGNORE_CASE)
        }

        private const val TARGET_DIR = "dir"
    }

    /**
     * Resolves the local File object for a given model.
     * Handles both directory-based (Vosk) and file-based (Whisper/NLU) models.
     */
    fun resolveLocalFile(modelId: String, engineKey: String): File? {
        val rootDir = context.getExternalFilesDir(null) ?: return null
        val extension = RemoteModelRegistry.getExtension(engineKey)

        return if (RemoteModelRegistry.isArchiveEngine(engineKey)) {
            // Archive engines: downloaded compressed, extracted to a directory named just modelId (no extension)
            File(rootDir, modelId)
        } else {
            // File-based engines: model stored as modelId + extension
            File(rootDir, "$modelId$extension")
        }
    }

    /**
     * Copies a model the user picked into the models directory, and returns where it landed.
     *
     * Importing lives here because this class owns where models are kept — the same directory the
     * resolver reads and the cleanup sweeps. It used to be done in three other places instead: a
     * copy in the settings view model, a hand-rolled one in the activity, and, for directory-based
     * engines, no copy at all — those stored `uri.path` of a picked *tree*, which is a document-id
     * string like `/tree/primary:Download/vosk-model` and not a filesystem path, so nothing could
     * ever open it. The import reported success and left a value no reader could use.
     *
     * Directory-based engines are copied recursively: Vosk's `Model(path)` opens a real directory,
     * and the picked one is only reachable through the provider that granted it, for as long as
     * that grant lasts.
     *
     * @return the imported artefact, or null if nothing could be read — the caller reports that
     *         rather than storing a path to something absent.
     */
    /**
     * What became of an import, in enough detail to tell the user.
     *
     * A null return said only "no", and the screen turned that into "download failed" — the wrong
     * words for a file the user picked from their own storage, and no help at all in working out
     * what was wrong with it.
     */
    sealed interface ImportOutcome {
        /** [fromArchive] when the model was unpacked here rather than copied as it was found. */
        data class Accepted(val file: File, val fromArchive: Boolean = false) : ImportOutcome
        /** [expected] is what the engine declares one of its models looks like. */
        data class WrongKind(val picked: String?, val expected: String) : ImportOutcome
        data object Empty : ImportOutcome
        data class Failed(val message: String?) : ImportOutcome
    }

    fun importCustomModel(uri: Uri, engineKey: String, langCode: String? = null): ImportOutcome {
        val rootDir = context.getExternalFilesDir(null)
            ?: return ImportOutcome.Failed("no app storage")

        return try {
            val declaredEntry = RemoteModelRegistry.getEntryPoint(engineKey)
            if (RemoteModelRegistry.isArchiveEngine(engineKey)) {
                val suffix = langCode?.let { "_$it" }.orEmpty()
                val dest = File(rootDir, "${engineKey}_custom$suffix")
                dest.deleteRecursively()

                /*
                 * Either the extracted model or the archive it came in.
                 *
                 * What upstream publishes is the archive — a Vosk model is a .zip on the vendor's
                 * own download page — so requiring the user to unpack it first meant the file they
                 * actually have was the one thing the import would not take.
                 */
                val extension = RemoteModelRegistry.getExtension(engineKey)
                if (!DocumentsContract.isTreeUri(uri)) {
                    val picked = displayNameOf(uri)
                    if (picked != null && !picked.endsWith(extension, ignoreCase = true)) {
                        Logger.log("Imported file '$picked' is not a $extension for $engineKey", TAG)
                        return ImportOutcome.WrongKind(picked, extension)
                    }
                    val staged = File(rootDir, "${engineKey}_import$suffix$extension")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            staged.outputStream().use { output -> input.copyTo(output) }
                        } ?: return ImportOutcome.Failed("could not read the archive")
                        dest.mkdirs()
                        extractArchive(staged, dest, extension)
                    } finally {
                        staged.delete()
                    }
                    return if (declaredEntry != null && resolveEntry(dest, declaredEntry) == null) {
                        Logger.log("Imported archive for $engineKey has no ${declaredEntry.match}", TAG)
                        dest.deleteRecursively()
                        ImportOutcome.WrongKind(picked, declaredEntry.match.orEmpty())
                    } else {
                        ImportOutcome.Accepted(dest, fromArchive = true)
                    }
                }

                val treeId = DocumentsContract.getTreeDocumentId(uri)
                copyDocumentTree(uri, DocumentsContract.buildDocumentUriUsingTree(uri, treeId), dest)
                if (dest.listFiles().isNullOrEmpty()) {
                    Logger.log("Imported directory for $engineKey is empty", TAG)
                    dest.deleteRecursively()
                    ImportOutcome.Empty
                } else if (declaredEntry != null && resolveEntry(dest, declaredEntry) == null) {
                    // The engine says what one of its models looks like — Vosk's `am` directory,
                    // Piper's `.onnx`. A directory that has none of it is not a model for this
                    // engine, and copying it in would leave the user with a selection that fails
                    // natively at load with nothing pointing back at the folder they picked.
                    Logger.log("Imported directory for $engineKey has no ${declaredEntry.match}", TAG)
                    dest.deleteRecursively()
                    ImportOutcome.WrongKind(displayNameOf(uri), declaredEntry.match.orEmpty())
                } else {
                    ImportOutcome.Accepted(dest)
                }
            } else {
                val extension = RemoteModelRegistry.getExtension(engineKey)
                val picked = displayNameOf(uri)
                if (extension.isNotBlank() && picked != null && !picked.endsWith(extension, ignoreCase = true)) {
                    // Copying it anyway would rename it to the extension the engine expects, so a
                    // wrong file would arrive looking exactly like a right one.
                    Logger.log("Imported file '$picked' is not a $extension for $engineKey", TAG)
                    return ImportOutcome.WrongKind(picked, extension)
                }
                val dest = File(rootDir, "$engineKey$extension")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return ImportOutcome.Failed("could not read the file")
                ImportOutcome.Accepted(dest)
            }
        } catch (e: Exception) {
            Logger.log("Custom model import failed for $engineKey: ${e.message}", TAG)
            ImportOutcome.Failed(e.message)
        }
    }

    /** The name the picker shows for [uri], so an import can be judged before it is copied. */
    private fun displayNameOf(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Walks a picked tree, mirroring its structure — a Vosk model is a directory of directories. */
    private fun copyDocumentTree(treeUri: Uri, documentUri: Uri, dest: File) {
        dest.mkdirs()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(documentUri)
        )
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                val name = cursor.getString(1)
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    copyDocumentTree(treeUri, childUri, File(dest, name))
                } else {
                    context.contentResolver.openInputStream(childUri)?.use { input ->
                        File(dest, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    /**
     * Generic download method.
     */
    fun downloadModel(modelId: String, url: String, engineKey: String): Long {
        val extension = RemoteModelRegistry.getExtension(engineKey)
        val fileName = "$modelId$extension"

        // Pre-flight check
        val localFile = resolveLocalFile(modelId, engineKey)
        if (localFile?.exists() == true) {
            Logger.log("Model already exists: $modelId ($engineKey), skipping download", TAG)
            return -1L
        }

        // Clean up leftover download files to prevent DownloadManager adding -N suffix
        val isArchiveEngine = RemoteModelRegistry.isArchiveEngine(engineKey)
        val downloadDir = if (isArchiveEngine) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } else {
            context.getExternalFilesDir(null)
        }
        val leftoverFile = File(downloadDir, fileName)
        if (leftoverFile.exists()) {
            leftoverFile.delete()
            Logger.log("Cleaned up leftover file: ${leftoverFile.absolutePath}", TAG)
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading Model ($modelId)")
            .setDescription("Preparing offline engine: $engineKey")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        // Archive-based engines go to temporary Downloads dir for extraction, others directly to root
        val destination = if (isArchiveEngine) Environment.DIRECTORY_DOWNLOADS else null
        request.setDestinationInExternalFilesDir(context, destination, fileName)

        // LLM-specific flags
        if (RemoteModelRegistry.isLlmEngine(engineKey)) {
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)
        }

        val downloadId = downloadManager.enqueue(request)
        Logger.log("Download started: modelId=$modelId, engine=$engineKey, downloadId=$downloadId", TAG)
        return downloadId
    }

    /**
     * Generic delete method.
     */
    /**
     * Removes the file or directory a custom import copied in, given the path that was stored.
     *
     * Clearing the selection used to leave it on disk. Nothing else knew the file: it is not a
     * registry model, so no delete path reached it, and the cleanup sweep protects exactly the paths
     * that are still stored — so a cleared model became collectable only if the user happened to run
     * "delete unused models" afterwards. A Vosk directory is a gigabyte; it should go when the
     * selection that named it goes.
     */
    fun deleteCustomModel(path: String) {
        if (path.isBlank()) return
        val file = File(path)
        val root = context.getExternalFilesDir(null)?.canonicalFile ?: return
        // An imported path is one this app wrote under its own directory. Anything else is a
        // pointer we did not create, and deleting by a stored string is not something to do blind.
        if (!file.canonicalFile.path.startsWith(root.path + File.separator)) {
            Logger.log("Refusing to delete custom model outside app storage: $path", TAG)
            return
        }
        if (file.exists()) {
            Logger.log("Deleting custom model at ${file.absolutePath}", TAG)
            file.deleteRecursively()
        }
    }

    /**
     * Moves an imported model to the name its language gives it, returning the new path.
     *
     * The language is part of the file name because it is part of the key the model is stored
     * under; re-filing it is a rename rather than a second copy of a model that may be a gigabyte.
     */
    fun renameCustomModel(currentPath: String, engineKey: String, langCode: String): String? {
        val current = File(currentPath)
        if (!current.exists()) return null
        val root = context.getExternalFilesDir(null) ?: return null
        val target = File(root, "${engineKey}_custom_$langCode")
        if (current.canonicalPath == target.canonicalPath) return currentPath
        target.deleteRecursively()
        return if (current.renameTo(target)) {
            Logger.log("Re-filed the import for $engineKey under $langCode", TAG)
            target.absolutePath
        } else {
            Logger.log("Could not rename ${current.name} to ${target.name}", TAG)
            null
        }
    }

    fun deleteModelFile(modelId: String, engineKey: String) {
        val file = resolveLocalFile(modelId, engineKey)
        if (file?.exists() == true) {
            Logger.log("Deleting model: $modelId ($engineKey) at ${file.absolutePath}", TAG)
            file.deleteRecursively()
        } else {
            Logger.log("Model file not found for deletion: $modelId ($engineKey)", TAG)
        }
    }

    /**
     * The file or directory this model's engine library must be handed, or null if the model is not
     * usable. See [resolveEntry] for why this is the only place that answers the question.
     *
     * An engine that declares no [EntryPoint] — a schema older than the field — falls back to plain
     * existence rather than guessing at a layout. That is deliberately weaker than a structural
     * check and never destructive: not knowing what to look for must not become grounds for
     * deleting the user's download.
     */
    fun resolveEntryPoint(modelId: String, engineKey: String): File? {
        val root = resolveLocalFile(modelId, engineKey) ?: return null
        val entry = RemoteModelRegistry.getEntryPoint(engineKey)
            ?: return root.takeIf { it.exists() }
        return resolveEntry(root, entry)
    }

    /**
     * Whether a model previously recorded as downloaded is actually usable on disk.
     *
     * Archives get the per-engine structural check in [validateModel], which also purges a directory
     * left corrupt by an interrupted extraction; a single file only has to exist, and would be
     * wrongly rejected by a validator that expects a directory. Callers ask this instead of
     * branching on packaging themselves — the branch is what has to stay consistent with the rest of
     * this class, and a caller that gets it wrong deletes a perfectly good model.
     */
    fun isModelUsable(modelId: String, engineKey: String): Boolean =
        if (RemoteModelRegistry.isArchiveEngine(engineKey)) {
            validateModel(modelId, engineKey)
        } else {
            resolveEntryPoint(modelId, engineKey) != null
        }

    /**
     * Makes a freshly downloaded artefact usable, whatever its packaging: archives are extracted,
     * single files are already in place. [onReady] runs once the model can be resolved on disk,
     * carrying the directory name for archives and null for single files.
     *
     * Callers must go through this rather than testing the extension themselves. The layout rules
     * live in [resolveLocalFile] and [downloadModel] in this class; a caller that re-derives them
     * can disagree with them, and the failure is silent — the download reports success while the
     * model is missing from where everything else looks for it.
     *
     * Extraction runs on a daemon thread because a broadcast receiver has ~10s before an ANR and
     * multi-GB models need far longer.
     */
    fun installDownloadedModel(modelId: String, engineKey: String, onReady: (dirName: String?) -> Unit) {
        if (!RemoteModelRegistry.isArchiveEngine(engineKey)) {
            onReady(null)
            return
        }
        Thread {
            try {
                unzipModel(modelId, engineKey) { success ->
                    Logger.log("Extraction ${if (success) "success" else "failed"} for $modelId", TAG)
                    onReady(modelId)
                }
            } catch (e: Exception) {
                Logger.log("Extraction thread error: ${e.message}", TAG)
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * Extracts archive-based models (.zip or .tar.bz2) from temporary downloads to app root.
     * @param modelId Model identifier (without extension)
     * @param engineKey Engine key from models.json
     */
    fun unzipModel(modelId: String, engineKey: String, onComplete: (Boolean) -> Unit) {
        val extension = RemoteModelRegistry.getExtension(engineKey)
        val archiveFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$modelId$extension")
        val targetDir = resolveLocalFile(modelId, engineKey) ?: return onComplete(false)

        if (!archiveFile.exists()) {
            Logger.log("Extraction failed: archive file not found: ${archiveFile.absolutePath}", TAG)
            onComplete(false)
            return
        }

        // Write a .downloading marker so we can detect incomplete extractions after a crash
        val marker = File(targetDir.parentFile, "$modelId.downloading")
        try {
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            marker.writeText("extracting")

            extractArchive(archiveFile, targetDir, extension)

            marker.delete()
            archiveFile.delete()
            Logger.log("Extraction successful: $modelId", TAG)
            onComplete(true)
        } catch (e: Exception) {
            Logger.log("Extraction failed for $modelId: ${e.message}", TAG)
            // Leave the marker — cleanup will detect it on next startup
            onComplete(false)
        }
    }

    /**
     * Unpacks [archive] into [targetDir], by the packaging its name declares.
     *
     * Shared by the download path and the import path: what upstream publishes is an archive, so a
     * user importing a model has the same file the downloader would have fetched, and it has to be
     * opened the same way. The nested-directory flatten belongs here for the same reason — an
     * archive that wraps its contents in one folder is a property of the archive, not of how it
     * arrived.
     */
    private fun extractArchive(archive: File, targetDir: File, extension: String) {
        if (extension.equals(".tar.bz2", ignoreCase = true)) {
            extractTarBz2(archive, targetDir)
        } else {
            ZipInputStream(FileInputStream(archive)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val newFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos -> zis.copyTo(fos) }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        flattenNestedDir(targetDir)
    }

    /**
     * Extracts a .tar.bz2 archive to the target directory.
     */
    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        var entry: TarArchiveEntry? = tis.nextEntry as? TarArchiveEntry
                        while (entry != null) {
                            val newFile = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                newFile.mkdirs()
                            } else {
                                newFile.parentFile?.mkdirs()
                                FileOutputStream(newFile).use { fos ->
                                    tis.copyTo(fos)
                                }
                            }
                            entry = tis.nextEntry as? TarArchiveEntry
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a model directory is complete and valid.
     * Deletes incomplete/corrupt directories (e.g. if app crashed during unzip).
     * Returns true if the model is valid and ready to use.
     */
    fun validateModel(modelId: String, engineKey: String): Boolean {
        val targetDir = resolveLocalFile(modelId, engineKey) ?: return false
        val marker = File(targetDir.parentFile, "$modelId.downloading")

        // If marker exists, the extraction was interrupted — clean up
        if (marker.exists()) {
            Logger.log("Model $modelId has incomplete extraction marker — deleting corrupt directory", TAG)
            targetDir.deleteRecursively()
            marker.delete()
            return false
        }

        if (!targetDir.exists() || !targetDir.isDirectory) return false

        // Same resolution the engine will use, so the two cannot disagree about a directory this
        // branch is willing to delete. An engine that declares no entry point resolves on existence
        // alone and therefore passes: not knowing what to look for must not become grounds for
        // deleting the user's download.
        if (resolveEntryPoint(modelId, engineKey) != null) return true

        // Declared layout, extraction finished (no marker), and the artefact is still not there —
        // the directory is genuinely incomplete. Removing it is what lets a re-download fix it.
        Logger.log("Model $modelId does not satisfy its entry point — deleting incomplete model", TAG)
        targetDir.deleteRecursively()
        return false
    }

    /**
     * Agnostic cleanup of unused models.
     * Protects only active voice + intent models. Everything else is purged.
     */
    suspend fun deleteUnusedModels(
        settingsRepo: SettingsRepository,
        activeVoiceModelId: String?,
        activeIntentModelId: String?,
        appStateManager: AppStateManager? = null,
        activeWakeModelId: String? = null
    ) {
        val rootDir = context.getExternalFilesDir(null) ?: return
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

        // 1. Build protected set: only active models, resolved against their actual engine
        val protectedNames = mutableSetOf<String>()

        // Essential system items
        protectedNames.addAll(listOf("Download", "transcriptions", "logs"))

        val snapshot = settingsRepo.getSettingsSnapshot()

        // Protect active voice model — resolve only against the active voice engine
        activeVoiceModelId?.let { id ->
            resolveLocalFile(id, snapshot.voiceProcessor)?.let { protectedNames.add(it.name) }
        }

        // Protect active intent model — resolve only against the active intent engine
        activeIntentModelId?.let { id ->
            resolveLocalFile(id, snapshot.aiProcessor)?.let { protectedNames.add(it.name) }
        }

        // Protect wake word model — resolve only against the active wake word engine
        activeWakeModelId?.let { id ->
            resolveLocalFile(id, snapshot.wakeWordEngineType)?.let { protectedNames.add(it.name) }
        }

        // Protect the models the user chose as fallbacks. "Unused" has to mean neither active nor
        // fallback: a model picked precisely so it is there when the primary fails is not unused,
        // and deleting it silently removed the safety net the user had set up.
        snapshot.defaultVoiceFallbackProcessor?.let { proc ->
            snapshot.defaultVoiceFallbackModel?.let { id ->
                resolveLocalFile(id, proc)?.let { protectedNames.add(it.name) }
            }
        }
        snapshot.defaultIntentFallbackProcessor?.let { proc ->
            snapshot.defaultIntentFallbackModel?.let { id ->
                resolveLocalFile(id, proc)?.let { protectedNames.add(it.name) }
            }
        }

        // Protect every model the user imported themselves.
        //
        // These are not "downloaded" in the sense this cleanup means — nothing here can re-fetch
        // them, and the file the user picked may be the only copy they have. The single-file import
        // copies into this very directory under a name derived from the engine, so the sweep below
        // reached it by name; and because the active model id is a registry id rather than that
        // file, none of the protections above ever resolved to it. Selecting a custom model and
        // then running cleanup deleted it, with the selection left pointing at nothing.
        snapshot.customModelPaths.values
            .filter { it.isNotBlank() }
            .forEach { protectedNames.add(java.io.File(it).name) }

        val engineKeys = RemoteModelRegistry.getEngineTypes()

        Logger.log("Cleanup started. Protected items: $protectedNames", CLEANUP_TAG)

        // 2. Wipe EVERYTHING else in root
        rootDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name in protectedNames) {
                Logger.log("Keeping protected item: $name", CLEANUP_TAG)
                return@forEach
            }

            Logger.log("DELETING unused item: ${file.absolutePath}", CLEANUP_TAG)
            file.deleteRecursively()

            // Sync settings: extract modelId from filename
            // For file-based engines: strip extension. For zip engines: directory name IS the modelId.
            var modelId = name
            engineKeys.forEach { key ->
                val ext = RemoteModelRegistry.getExtension(key)
                if (ext.isNotBlank() && name.endsWith(ext)) {
                    modelId = name.removeSuffix(ext)
                }
            }
            
            settingsRepo.setModelDownloaded(modelId, false)
            // No fallback reassignment here any more: a fallback model is now protected above, so
            // nothing this loop deletes can be one. The block that used to live here moved the
            // checkbox onto the active model by writing the *primary's* processor as the fallback
            // processor — a value that can never be a working fallback, since the cascade skips a
            // fallback equal to the primary and the voice path never reads it at all.
        }

        // 3. Clean temporary Downloads
        downloadsDir?.listFiles()?.forEach { file ->
            // Protected first, here too. This sweep matches on extension alone, and it is only the
            // separation between `files/` and `files/Download` that keeps it away from real models
            // — a directory layout this code does not control and does not check. Asking the same
            // question step 2 asks costs nothing and removes the dependency on that accident.
            if (file.name in protectedNames) return@forEach

            val isKnownZip = engineKeys.any { key ->
                val ext = RemoteModelRegistry.getExtension(key)
                ext.isNotBlank() && file.name.endsWith(ext)
            }
            if (isKnownZip) {
                file.delete()
                Logger.log("Deleted ZIP from downloads: ${file.name}", CLEANUP_TAG)
            }
        }

        Logger.log("Cleanup complete.", CLEANUP_TAG)
        appStateManager?.refreshAll()
    }
}
