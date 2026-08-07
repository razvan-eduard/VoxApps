package com.voxapps.ipc

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay

/**
 * Wakes satellite apps out of Android's "stopped" state so they can answer an IPC broadcast, then
 * hands focus back to the caller.
 *
 * An app the user force-stopped (or never launched since install) is flagged stopped, and a stopped
 * app receives no broadcasts — so an export/import/schema request to it silently returns nothing.
 * Launching its activity clears the flag. The timings below are the load-bearing part:
 *
 * - Each target needs **its own** `startActivity` call. Batching them into one `startActivities`
 *   avoids BAL blocking but never lets the non-final entries actually resume — confirmed via
 *   `dumpsys`, their stopped flag stayed true even though a process had spawned. Only reaching
 *   RESUMED (visible), not merely started, clears it.
 * - [LAUNCH_SETTLE_MS] has to be long enough for a real activity transition, but a delay much past
 *   ~1s pushes the caller's next `startActivity` outside its post-tap background-activity-launch
 *   grace window — confirmed via logcat, "Background activity launch blocked!" appeared at 700ms.
 *   350ms threads that needle for every call in the chain.
 * - [REFOCUS_SETTLE_MS] then lets the caller's own activity come back to the foreground before the
 *   IPC requests go out.
 *
 * Lives in `:core:ipc` because this is the precondition for the broadcasts this module defines, and
 * the sequence was previously copy-pasted at three call sites across two apps (Hub's export and
 * import retries, Commander's schema refresh) with the tuning rationale written out only once.
 */
object BalGraceFlash {
    const val LAUNCH_SETTLE_MS = 350L
    const val REFOCUS_SETTLE_MS = 300L

    /**
     * Launches each of [packages] in turn, then returns focus to [context]'s own app.
     *
     * Packages with no launcher intent are skipped — there is nothing to flash, and a satellite
     * without a launchable activity was never in the stopped state to begin with.
     */
    suspend fun flashThenRefocus(context: Context, packages: Collection<String>) {
        // startActivity goes through the caller's own [context], deliberately not
        // context.applicationContext. An Activity context supplies a sourceRecord to the framework's
        // activity starter and takes an earlier allow-path in the background-activity-launch check;
        // from an application context the decision falls back to process-state heuristics. Since the
        // timings above were tuned against observed "Background activity launch blocked!" messages,
        // the identity of the caller is not a free variable to change. Callers that only have an
        // application context (Commander's registry) pass that and are unaffected.
        for (pkg in packages) {
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                delay(LAUNCH_SETTLE_MS)
            }
        }
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        delay(REFOCUS_SETTLE_MS)
    }
}
