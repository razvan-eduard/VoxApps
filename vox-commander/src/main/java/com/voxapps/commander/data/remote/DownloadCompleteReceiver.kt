package com.voxapps.commander.data.remote

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.commander.utils.Logger
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
        val cursor = downloadManager.query(query)

        if (cursor.moveToFirst()) {
            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (uriIndex != -1) {
                val uriString = cursor.getString(uriIndex)
                val filePath = uriString?.removePrefix("file://")

                if (filePath != null) {
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
            }
        }
        cursor.close()
    }

    companion object {
        private const val TAG = "DownloadCompleteReceiver"
        const val ACTION_DOWNLOAD_COMPLETE_LOCAL = "com.voxapps.commander.DOWNLOAD_COMPLETE_LOCAL"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_FILE_PATH = "file_path"
    }
}
