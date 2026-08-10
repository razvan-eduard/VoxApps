package com.voxapps.commander.data.remote

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.logging.Logger
import java.io.File

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.log("DownloadCompleteReceiver onReceive called", TAG)
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id == -1L) {
            Logger.log("Download complete: invalid id=$id", TAG)
            return
        }

        Logger.log("Download complete: $id", TAG)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        // The cursor is read out fully here and closed before any of the work below starts. The old
        // form kept it open across the whole method and closed it by hand on each exit path, which
        // missed one — the "could not match file to any engine" return further down left it open —
        // and leaked it outright if anything in between threw. Nothing past this block needs it, so
        // its lifetime has no reason to span the unzip thread's spawn.
        val filePath = downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            // A failed download (bad URL, server error, network drop) also fires this broadcast —
            // it doesn't mean success. Logged here (this receiver has no UI to surface it to; see
            // ModelManagementViewModel.downloadError for the user-facing side of this same check)
            // so a silent failure is at least visible in the verbose log instead of just stopping
            // here with zero trace.
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex != -1 && cursor.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) {
                val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val reason = if (reasonIndex != -1) cursor.getInt(reasonIndex) else -1
                Logger.log("Download $id did not succeed (status=${cursor.getInt(statusIndex)}, reason=$reason), skipping file handling", TAG)
                return@use null
            }

            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (uriIndex == -1) return@use null
            cursor.getString(uriIndex)?.removePrefix("file://")
        } ?: return

        Logger.log("Downloaded file: $filePath", TAG)

        // Dynamically match the downloaded file to an engine by extension
        val fileName = File(filePath).name
        val matchedEngineKey = RemoteModelRegistry.getEngineTypes().firstOrNull { key ->
            val ext = RemoteModelRegistry.getExtension(key)
            ext.isNotBlank() && fileName.endsWith(ext, ignoreCase = true)
        }

        if (matchedEngineKey == null) {
            Logger.log("Could not match file '$fileName' to any engine, ignoring", TAG)
            return
        }

        val ext = RemoteModelRegistry.getExtension(matchedEngineKey)
        val modelId = fileName.removeSuffix(ext)
        Logger.log("Matched engine: $matchedEngineKey, modelId: $modelId", TAG)

        // Whether this artefact needs unpacking is ModelDownloader's call, not the receiver's — it
        // owns where a model lands and what shape it takes on disk. The receiver only announces
        // the result once the model is actually usable.
        // A rejection has to be announced too. The success broadcast is what clears the progress
        // row, so staying silent about a refused download leaves it spinning until the screen is
        // rebuilt — the download looks stuck rather than refused.
        val announce = { dirName: String?, rejected: String? ->
            val localIntent = Intent(ACTION_DOWNLOAD_COMPLETE_LOCAL).apply {
                putExtra(EXTRA_DOWNLOAD_ID, id)
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra("model_type", matchedEngineKey)
                dirName?.let { putExtra("directory_name", it) }
                rejected?.let { putExtra(EXTRA_REJECTED, it) }
            }
            Logger.log("Sending local broadcast for $matchedEngineKey: id=$id, dir=$dirName, rejected=$rejected", TAG)
            context.sendBroadcast(localIntent)
        }

        ModelDownloader(context).installDownloadedModel(
            modelId,
            matchedEngineKey,
            onRejected = { reason -> announce(null, reason) }
        ) { dirName ->
            announce(dirName, null)
        }
    }

    companion object {
        private const val TAG = "DownloadCompleteReceiver"
        const val ACTION_DOWNLOAD_COMPLETE_LOCAL = "com.voxapps.commander.DOWNLOAD_COMPLETE_LOCAL"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_FILE_PATH = "file_path"
        /** Present when the artefact was refused — today, a checksum the schema declared and the
         *  bytes did not match. Its absence means the download succeeded. */
        const val EXTRA_REJECTED = "rejected"
    }
}
