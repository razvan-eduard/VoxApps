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

        if (ext.equals(".zip", ignoreCase = true)) {
            // ZIP-based engines need unzip before signaling — run on daemon thread
            // to avoid blocking onReceive(). No goAsync() since it has a ~10s ANR timeout
            // which is too short for large models (2GB+).
            Thread {
                try {
                    val downloader = ModelDownloader(context)
                    downloader.unzipModel(modelId, matchedEngineKey) { success ->
                        Logger.log("Unzip ${if (success) "success" else "failed"} for $modelId", TAG)
                        val localIntent = Intent(ACTION_DOWNLOAD_COMPLETE_LOCAL).apply {
                            putExtra(EXTRA_DOWNLOAD_ID, id)
                            putExtra(EXTRA_FILE_PATH, filePath)
                            putExtra("directory_name", modelId)
                            putExtra("model_type", matchedEngineKey)
                        }
                        Logger.log("Sending local broadcast for $matchedEngineKey: action=$ACTION_DOWNLOAD_COMPLETE_LOCAL, id=$id, dir=$modelId", TAG)
                        context.sendBroadcast(localIntent)
                    }
                } catch (e: Exception) {
                    Logger.log("Unzip thread error: ${e.message}", TAG)
                }
            }.apply { isDaemon = true; start() }
        } else {
            // File-based engines (e.g. stt_whisper, nlu_llm) are ready as-is
            val localIntent = Intent(ACTION_DOWNLOAD_COMPLETE_LOCAL).apply {
                putExtra(EXTRA_DOWNLOAD_ID, id)
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra("model_type", matchedEngineKey)
            }
            Logger.log("Sending local broadcast for $matchedEngineKey: action=$ACTION_DOWNLOAD_COMPLETE_LOCAL, id=$id", TAG)
            context.sendBroadcast(localIntent)
        }
    }

    companion object {
        private const val TAG = "DownloadCompleteReceiver"
        const val ACTION_DOWNLOAD_COMPLETE_LOCAL = "com.voxapps.commander.DOWNLOAD_COMPLETE_LOCAL"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
