package com.voxapps.commander.domain.intent.handler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.commander.domain.integration.SatelliteRouting
import com.voxapps.commander.domain.integration.VoxSatelliteRegistry
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.voice.TtsManager
import com.voxapps.commander.utils.Logger
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult

/**
 * Generic bridge from the NLU to ANY Vox satellite discovered at runtime (via
 * [VoxSatelliteRegistry]). The satellite that advertised the intent's [domain] receives a
 * [VoxCommand] over the JSON bus — Commander holds all the NLU; the satellite just executes.
 *
 *  - `create` → fire-and-forget append (note/task/etc. content taken from the utterance).
 *  - `read`   → ordered broadcast; the returned text is spoken with Commander's TTS.
 *
 * No per-app code lives here — a user's own contract app is routed the same way once it's scanned.
 */
class SatelliteHandler : IntentHandler {

    override fun canHandle(intent: NluIntent): Boolean = VoxSatelliteRegistry.handles(intent.domain)

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        val candidates = VoxSatelliteRegistry.candidatesForDomain(intent.domain)
        // resolvedApp already encodes explicit/alias/star (via AppResolver in IntentRouter). Use it as
        // both the explicit and star hint; SatelliteRouting then applies first-party > single > fallback.
        val decision = SatelliteRouting.pick(
            candidates = candidates,
            starredPkg = resolvedApp?.packageName,
            explicitPkg = resolvedApp?.packageName
        )
        val pkg = decision.packageName ?: return false
        if (decision.ambiguous) {
            Logger.log("Ambiguous ${intent.domain}: ${candidates.size} third-party apps, no star — routing to $pkg (TODO: voice disambiguation)", TAG)
        }
        return when (intent.action) {
            VoxIpc.OP_CREATE -> create(context, pkg, intent)
            VoxIpc.OP_READ -> read(context, pkg, intent)
            else -> false
        }
    }

    private fun create(context: Context, pkg: String, intent: NluIntent): Boolean {
        val text = intent.extras["note_text"]?.takeIf { it.isNotBlank() }
            ?: intent.logicalSubject?.takeIf { it.isNotBlank() }
            ?: intent.actionVerb.takeIf { it.isNotBlank() }
            ?: return false

        val payload = VoxCommand(
            op = VoxIpc.OP_CREATE,
            text = text,
            category = intent.category?.takeIf { it.isNotBlank() },
            domain = intent.domain
        ).toJson()
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_COMMAND).setPackage(pkg).putExtra(VoxIpc.EXTRA_PAYLOAD, payload)
        )
        Logger.log("Sent create to $pkg [${intent.domain}] (${text.length} chars)", TAG)
        return true
    }

    private fun read(context: Context, pkg: String, intent: NluIntent): Boolean {
        val payload = VoxCommand(op = VoxIpc.OP_READ, domain = intent.domain).toJson()
        context.sendOrderedBroadcast(
            Intent(VoxIpc.ACTION_COMMAND).setPackage(pkg).putExtra(VoxIpc.EXTRA_PAYLOAD, payload),
            null,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val toSpeak = VoxResult.fromJson(resultData)?.text?.takeIf { it.isNotBlank() } ?: return
                    Logger.log("$pkg [${intent.domain}] read → TTS (${toSpeak.length} chars)", TAG)
                    TtsManager.speak(toSpeak)
                }
            },
            null, 0, null, null
        )
        return true
    }

    private companion object {
        const val TAG = "SatelliteHandler"
    }
}
