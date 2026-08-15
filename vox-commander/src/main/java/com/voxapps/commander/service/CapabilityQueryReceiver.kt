package com.voxapps.commander.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.commander.VoxApplication
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.ipc.VoxCapabilityClient
import com.voxapps.ipc.VoxIpc

/**
 * Exported entry point for [VoxIpc.ACTION_CAPABILITY_QUERY]: any first-party app (e.g. Vision, before
 * deciding whether to attach a scanned photo) can synchronously ask which capabilities Commander's
 * currently-configured engine has. Global engine state, not per-satellite data — kept separate from
 * the [com.voxapps.ipc.VoxSatelliteSchema] fetch on purpose (see the collapsed voice-command plan).
 * Cheap and purely local (a settings read + a set lookup), so unlike [LlmHookReceiver] this answers
 * synchronously within the ordered-broadcast window rather than deferring to a WorkManager job.
 */
class CapabilityQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_CAPABILITY_QUERY) return
        val container = (context.applicationContext as VoxApplication).container
        val processor = container.settingsRepository.getSettingsSnapshot().aiProcessor
        val multimodal = RemoteModelRegistry.isMultimodal(processor)
        val local = RemoteModelRegistry.isLocalEngine(processor)
        // Declared per engine, like every other capability here: what a caller may put in one
        // prompt is a property of the engine serving it, and the engines are data.
        val longPrompt = RemoteModelRegistry.hasCapability(processor, "long_prompt")
        setResult(
            Activity.RESULT_OK,
            VoxCapabilityClient.buildReply(multimodal, local, longPrompt).toJson(),
            null
        )
    }
}
