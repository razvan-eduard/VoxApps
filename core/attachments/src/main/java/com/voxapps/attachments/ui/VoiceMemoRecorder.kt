package com.voxapps.attachments.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voxapps.attachments.AttachmentFileStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay

/** Voice notes auto-stop here rather than recording until the disk fills. */
private const val MAX_DURATION_MS = 10 * 60 * 1000

/**
 * One recording session into [outputFile] — AAC in an MPEG-4 container, mono, speech-grade
 * bitrate. [stop] returns the elapsed milliseconds; [cancel] deletes the file. A recording the
 * encoder refuses to finalize (stopped near-instantly) is treated as cancelled.
 */
private class VoiceMemoRecorder(context: Context, val outputFile: File) {

    @Suppress("DEPRECATION")
    private val recorder =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder())
            .apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(64_000)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(outputFile.absolutePath)
            }

    private var startedAt = 0L

    fun start(onMaxDuration: () -> Unit): Boolean = try {
        recorder.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration()
        }
        recorder.prepare()
        recorder.start()
        startedAt = SystemClock.elapsedRealtime()
        true
    } catch (e: Exception) {
        runCatching { recorder.release() }
        outputFile.delete()
        false
    }

    /** Returns the recording's length in ms, or 0 when nothing usable was captured. */
    fun stop(): Long {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val finalized = runCatching { recorder.stop() }.isSuccess
        runCatching { recorder.release() }
        if (!finalized || elapsed < 500) {
            outputFile.delete()
            return 0L
        }
        return elapsed
    }

    fun cancel() {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        outputFile.delete()
    }
}

/**
 * The in-progress recording dialog: pulsing dot, elapsed time, stop or cancel. All labels come
 * from the caller — this module carries no translations, same as [AttachmentsSection].
 */
@Composable
private fun RecordVoiceDialog(
    recordingLabel: String,
    stopLabel: String,
    cancelLabel: String,
    onStop: (durationMs: Long) -> Unit,
    onCancel: () -> Unit,
    recorder: VoiceMemoRecorder
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(200)
        }
    }
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "recAlpha"
    )

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(recordingLabel) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(12.dp)
                            .alpha(pulse)
                            .background(Color(0xFFD32F2F), CircleShape)
                    )
                    Text(
                        text = formatElapsed(elapsedMs),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStop(recorder.stop()) }) { Text(stopLabel) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(cancelLabel) }
        }
    )
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Returns a callback that records a voice note into `filesDir/<dirName>/voice_<uuid>.m4a` and
 * hands the result to [onRecorded]. Owns the whole flow: the RECORD_AUDIO runtime ask (mirroring
 * [rememberCameraCaptureLauncher]'s CAMERA flow — the permission must also be declared in the
 * calling app's manifest), the recording dialog, and cleanup when the recording is cancelled or
 * too short to keep.
 */
@Composable
fun rememberVoiceMemoLauncher(
    dirName: String,
    recordingLabel: String,
    stopLabel: String,
    cancelLabel: String,
    onPermissionDenied: () -> Unit,
    onRecorded: (fileName: String, durationMs: Long) -> Unit
): () -> Unit {
    val context = LocalContext.current
    var activeRecorder by remember { mutableStateOf<VoiceMemoRecorder?>(null) }

    fun startRecording() {
        val fileName = "voice_${UUID.randomUUID()}.m4a"
        val file = AttachmentFileStore.file(context, dirName, fileName).apply { parentFile?.mkdirs() }
        val recorder = VoiceMemoRecorder(context, file)
        activeRecorder = recorder
    }

    activeRecorder?.let { recorder ->
        // Start exactly once per recorder instance; a failed start closes the dialog immediately.
        DisposableEffect(recorder) {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val started = recorder.start(onMaxDuration = {
                // The info listener fires on a recorder-owned thread; the caller's onRecorded
                // mutates editor state, so the hand-off hops to main like every other UI event.
                mainHandler.post {
                    if (activeRecorder !== recorder) return@post
                    val duration = recorder.stop()
                    activeRecorder = null
                    if (duration > 0) onRecorded(recorder.outputFile.name, duration)
                }
            })
            if (!started) activeRecorder = null
            onDispose { }
        }
        RecordVoiceDialog(
            recordingLabel = recordingLabel,
            stopLabel = stopLabel,
            cancelLabel = cancelLabel,
            onStop = { duration ->
                activeRecorder = null
                if (duration > 0) onRecorded(recorder.outputFile.name, duration)
            },
            onCancel = {
                recorder.cancel()
                activeRecorder = null
            },
            recorder = recorder
        )
    }

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else onPermissionDenied()
    }

    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
