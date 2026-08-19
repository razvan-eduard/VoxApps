package com.voxapps.commander.domain.intent.handler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.voxapps.commander.VoxApplication
import com.voxapps.commander.domain.integration.SatelliteRouting
import com.voxapps.commander.domain.integration.VoxSatelliteRegistry
import com.voxapps.commander.domain.intent.RawPromptOutcome
import com.voxapps.commander.domain.intent.interpreter.NluIntentParser
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.voice.TtsManager
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Generic bridge from the NLU to ANY Vox satellite discovered at runtime (via
 * [VoxSatelliteRegistry]). The satellite that advertised the intent's [domain] receives a
 * [VoxCommand] over the JSON bus — Commander holds all the NLU; the satellite just executes.
 *
 *  - `create` → fire-and-forget append (note/task/etc. content taken from the utterance), OR, when
 *    the satellite has a cached [com.voxapps.ipc.VoxSatelliteSchema] declaring
 *    `needsExtractionPass = true`, the collapsed path: Commander runs the extraction LLM call itself
 *    locally using the cached prompt template, then delivers the result via [VoxIpc.ACTION_LLM_RESULT]
 *    — the same wire shape the satellite's existing `LlmResultReceiver` already handles, so no
 *    satellite-side receiver changes are needed for delivery. Falls back to today's unmodified
 *    `VOX_COMMAND`/`OP_CREATE` flow whenever no cache exists yet (first-run) or the contract declares
 *    `needsExtractionPass = false` (e.g. Notes) — that flow was already optimal for those cases.
 *  - `read`   → ordered broadcast; the returned text is spoken with Commander's TTS.
 *
 * No per-app code lives here — a user's own contract app is routed the same way once it's scanned.
 */
class SatelliteHandler : IntentHandler {

    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

        val schema = VoxSatelliteRegistry.cachedSchema(pkg)
        if (schema != null && schema.needsExtractionPass) {
            runExtractionPassLocally(context, pkg, intent, schema)
            return true
        }

        // No cache yet (first-run fallback) or the contract declares no second pass (e.g. Notes) —
        // today's unmodified flow, already optimal for both cases.
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

    /**
     * The collapsed path: build the satellite's prompt from its cached template + this call's full
     * structured decomposition ([NluIntent.toDecompositionText] — not just [NluIntent.logicalSubject]),
     * run the extraction LLM call inside Commander's own process, then deliver the result the same
     * way [com.voxapps.commander.service.LlmHookWorker] already does today for the generic hook — so
     * the satellite's existing `LlmResultReceiver` needs no changes to consume it.
     */
    private fun runExtractionPassLocally(
        context: Context,
        pkg: String,
        intent: NluIntent,
        schema: com.voxapps.ipc.VoxSatelliteSchema
    ) {
        val appContext = context.applicationContext
        val container = (appContext as VoxApplication).container
        // Kept rather than inlined into buildPrompt: it is echoed back with the answer. This is the
        // only path where the satellite never sees what it is being answered about — it handed over
        // a template and Commander filled it — so without the echo, anything a rule on the device
        // could have settled from these words is unreachable, and every field has to come from the
        // model whether it needed to or not.
        val input = intent.toDecompositionText()
        val prompt = schema.buildPrompt(input)
        handlerScope.launch {
            val result = when (val outcome = container.llmHookEngineSelector.run(prompt)) {
                is RawPromptOutcome.Success -> VoxLlmResult(
                    task = schema.taskId,
                    status = VoxLlmResult.STATUS_SUCCESS,
                    rawJson = NluIntentParser.cleanGenericOutput(outcome.rawText),
                    input = input
                )
                is RawPromptOutcome.Error -> VoxLlmResult(
                    task = schema.taskId,
                    status = VoxLlmResult.STATUS_ERROR,
                    error = outcome.reason,
                    input = input
                )
            }
            deliverResult(appContext, pkg, result)
            Logger.log("Extraction pass for $pkg [${intent.domain}] -> status=${result.status}", TAG)
        }
    }

    private fun deliverResult(appContext: Context, pkg: String, result: VoxLlmResult) {
        val same = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.checkSignatures(appContext.packageName, pkg) == PackageManager.SIGNATURE_MATCH
        } catch (e: Exception) {
            false
        }
        if (!same) {
            Logger.log("Refusing to deliver extraction result — signature mismatch for $pkg", TAG)
            return
        }
        appContext.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_RESULT).setPackage(pkg).putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, result.toJson())
        )
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
