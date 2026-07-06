package com.voxcommander.app.domain.diagnostic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.voxcommander.app.utils.Logger
import com.voxcommander.app.utils.Strings

/**
 * Orchestrates a one-shot, isolated Vulkan GPU compatibility test. Binds to
 * [VulkanProbeService] (separate process) and asks it to run a real Whisper GPU
 * inference on the user's downloaded model, reporting the outcome via [onResult]:
 *
 *  - result ok=true            -> COMPATIBLE
 *  - result ok=false           -> INCOMPATIBLE (GPU produced an error)
 *  - process died before reply -> INCOMPATIBLE (native crash, isolated safely)
 *  - bind failed / timeout     -> UNDECIDED (caller may retry later)
 *
 * The probe persists nothing itself; the caller decides what to store based on [Outcome].
 */
class VulkanProbe(
    private val context: Context,
    private val modelPath: String,
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
            // Process lost. If no result was received, the GPU workload crashed it natively.
            if (!gotResult) finish(Outcome.INCOMPATIBLE, "process-crash")
        }
    }

    fun start() {
        try {
            val intent = Intent(context, VulkanProbeService::class.java)
            intent.putExtra(VulkanProbeService.EXTRA_MODEL_PATH, modelPath)
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
    }
}
