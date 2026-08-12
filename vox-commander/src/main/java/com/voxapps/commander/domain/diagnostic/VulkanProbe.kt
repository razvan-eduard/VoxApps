package com.voxapps.commander.domain.diagnostic

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings

/**
 * Orchestrates a one-shot, isolated GPU compatibility test for one engine (whisper or llama —
 * see [SettingsRepository.GPU_WHISPER]/[GPU_LLAMA]). Binds to [VulkanProbeService] (separate
 * process) and asks it to run a real GPU inference, reporting the outcome via [onResult]:
 *
 *  - result ok=true            -> COMPATIBLE
 *  - result ok=false           -> INCOMPATIBLE (GPU produced an error or a wrong answer)
 *  - process died before reply -> attributed via the OS exit record (below)
 *  - bind failed / timeout     -> UNDECIDED (caller may retry later)
 *
 * A death without a reply is not automatically the GPU's fault: the probe process is also an
 * ordinary LMK target. Where the OS can say why it died (API 30+), only a crash reads as
 * INCOMPATIBLE and a kill reads as UNDECIDED; where it cannot (API 29, or no record yet), the
 * death is treated as INCOMPATIBLE — in a process whose only work is the inference under test,
 * that is the overwhelmingly likely cause, and it matches what this probe has always meant.
 *
 * The probe persists nothing itself; the caller decides what to store based on [Outcome].
 */
class VulkanProbe(
    private val context: Context,
    private val modelPath: String,
    private val engine: String = SettingsRepository.GPU_WHISPER,
    private val onResult: (Outcome) -> Unit
) {
    enum class Outcome { COMPATIBLE, INCOMPATIBLE, UNDECIDED }

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var gotResult = false

    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == VulkanProbeService.MSG_RESULT) {
                gotResult = true
                finish(if (msg.arg1 == 1) Outcome.COMPATIBLE else Outcome.INCOMPATIBLE, "result")
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val m = Message.obtain(null, VulkanProbeService.MSG_RUN_TEST)
                m.replyTo = replyMessenger
                Messenger(service).send(m)
            } catch (e: Exception) {
                // Couldn't even start the test; don't penalize the device.
                Logger.log("Failed to start self-test: ${e.message}", TAG)
                finish(Outcome.UNDECIDED, "send-failed")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Process lost before a reply. Give the OS a beat to record the exit, then ask it why.
            if (!gotResult) {
                handler.postDelayed({
                    if (!finished) finish(attributeDeath(), "process-died")
                }, EXIT_RECORD_DELAY_MS)
            }
        }
    }

    /** Reads the probe process's exit record where the platform keeps one. */
    private fun attributeDeath(): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Outcome.INCOMPATIBLE
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val record = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .firstOrNull { it.processName.endsWith(":vulkanprobe") }
                ?: return Outcome.INCOMPATIBLE
            when (record.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_CRASH -> Outcome.INCOMPATIBLE
                else -> Outcome.UNDECIDED // LMK, user kill, dependency died — not the GPU's doing
            }
        } catch (e: Exception) {
            Logger.log("Exit-record lookup failed: ${e.message}", TAG)
            Outcome.INCOMPATIBLE
        }
    }

    fun start() {
        try {
            val intent = Intent(context, VulkanProbeService::class.java)
            intent.putExtra(VulkanProbeService.EXTRA_MODEL_PATH, modelPath)
            intent.putExtra(VulkanProbeService.EXTRA_ENGINE, engine)
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Logger.log("Could not bind VulkanProbeService", TAG)
                finish(Outcome.UNDECIDED, "bind-failed")
                return
            }
            handler.postDelayed({ if (!finished) finish(Outcome.UNDECIDED, "timeout") }, TIMEOUT_MS)
        } catch (e: Exception) {
            Logger.log("start() failed: ${e.message}", TAG)
            finish(Outcome.UNDECIDED, "start-exception")
        }
    }

    private fun finish(outcome: Outcome, reason: String) {
        if (finished) return
        finished = true
        Logger.log("Vulkan self-test done: outcome=$outcome reason=$reason", TAG)
        unbind()
        onResult(outcome)
    }

    private fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
            // Already unbound / never bound.
        }
    }

    companion object {
        private const val TAG = Strings.Tags.VULKAN_PROBE
        private const val TIMEOUT_MS = 30_000L
        private const val EXIT_RECORD_DELAY_MS = 400L
    }
}
