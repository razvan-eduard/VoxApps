package com.voxapps.commander.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.commander.VoxApplication
import com.voxapps.commander.domain.intent.RawPromptOutcome
import com.voxapps.commander.domain.intent.interpreter.NluIntentParser
import com.voxapps.commander.utils.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.ipc.VoxLlmResult

/**
 * Runs the actual (potentially slow) LLM call for a request handed off by [LlmHookReceiver], then
 * fires a separate, asynchronous explicit-intent reply back to the requesting satellite. Commander
 * stays domain-agnostic here — it applies only generic cleanup
 * ([NluIntentParser.cleanGenericOutput]) to the LLM's raw output, never validating or understanding
 * the `task`-specific shape the caller expects.
 *
 * Runs as a WorkManager job rather than a plain `Service` — on-device testing showed a plain,
 * non-foreground `Service` started from a `BroadcastReceiver` can be silently blocked from ever
 * running by OEM/Doze background-execution restrictions when Commander has no visible UI. WorkManager
 * goes through the system `JobScheduler`, which is specifically exempted from that restriction.
 */
class LlmHookWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "LlmHookWorker"

    override suspend fun doWork(): Result {
        val request = VoxLlmRequest.fromJson(inputData.getString(VoxIpc.EXTRA_LLM_PAYLOAD))
        if (request == null) {
            Logger.log("LlmHookWorker: no valid request in input data", TAG)
            return Result.failure()
        }

        Logger.log("LlmHookWorker: processing [${request.task}] from ${request.sourcePackage}", TAG)
        val container = (applicationContext as VoxApplication).container

        return try {
            val outcome = container.llmHookEngineSelector.run(request.promptText)
            val result = when (outcome) {
                is RawPromptOutcome.Success -> VoxLlmResult(
                    task = request.task,
                    status = VoxLlmResult.STATUS_SUCCESS,
                    rawJson = NluIntentParser.cleanGenericOutput(outcome.rawText)
                )
                is RawPromptOutcome.Error -> VoxLlmResult(
                    task = request.task,
                    status = VoxLlmResult.STATUS_ERROR,
                    error = outcome.reason
                )
            }
            replyToSource(request.sourcePackage, result)
            Logger.log("LlmHookWorker: replied with status=${result.status}", TAG)
            Result.success()
        } catch (e: Exception) {
            Logger.log("LlmHookWorker: processing failed: ${e.message}", TAG)
            replyToSource(
                request.sourcePackage,
                VoxLlmResult(task = request.task, status = VoxLlmResult.STATUS_ERROR, error = "Internal error: ${e.message}")
            )
            Result.failure()
        }
    }

    private fun replyToSource(sourcePackage: String, result: VoxLlmResult) {
        val pm = applicationContext.packageManager
        val same = try {
            @Suppress("DEPRECATION")
            pm.checkSignatures(applicationContext.packageName, sourcePackage) == PackageManager.SIGNATURE_MATCH
        } catch (e: Exception) {
            false
        }
        if (!same) {
            Logger.log("Refusing LLM reply — signature mismatch for $sourcePackage", TAG)
            return
        }
        applicationContext.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_RESULT)
                .setPackage(sourcePackage)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, result.toJson())
        )
    }
}
