package com.voxapps.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.voxapps.logging.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A satellite app that implements the Vox command contract, plus the capability it advertises via
 * `<meta-data>` on its receiver (the NLU [domain] it owns and the [actions] it accepts).
 */
data class VoxAppInfo(
    val packageName: String,
    val label: String,
    val domain: String?,
    val actions: List<String>,
    /** True if signed with the caller's own key — a "first-party" satellite that wins routing ties. */
    val isFirstParty: Boolean = false,
    /** Optional NLU extraction hint the satellite advertises; injected into the prompt. */
    val nluHint: String? = null
)

/**
 * Discovers installed apps that implement the Vox contract (an exported [VoxIpc.ACTION_COMMAND]
 * receiver) and reads their advertised capability from manifest meta-data — no per-app config on the
 * caller. A user's own app that declares the contract self-registers here. [ping] is the live
 * "does it actually respond" check. Shared between vox-commander's "Vox Apps" discovery and Vox Hub's
 * export/import app list — moved here (from vox-commander) so both can depend on it.
 */
object VoxAppsDiscovery {

    const val COMMANDER_PACKAGE = "com.voxapps.commander"

    /**
     * Every satellite's OCR-scan-cleanup and voice-parse flows (Vision's "send to X", Notes'/
     * Expenses'/Calendar's "Scan" entry points, category auto-merge, dedupe/cleanup) unconditionally
     * forward through Commander's generic LLM hook — there's no direct-save fallback. The broadcast to
     * a missing package just goes nowhere (no crash, no error), so callers gate on this rather than
     * let the user hit that silent dead end — either by hiding the entry point, or (the newer,
     * preferred pattern — see `:core:design`'s `rememberCommanderGate`) keeping it visible but dimmed,
     * with an explanatory message on tap.
     */
    fun isCommanderInstalled(context: Context): Boolean = isAppInstalled(context, COMMANDER_PACKAGE)

    /** General "is this package installed *and enabled*" check — [isCommanderInstalled] is the
     *  common case, but a scan flow also needs to know whether [VoxIpc.VISION_PACKAGE] itself is
     *  present before even attempting to launch it (a missing target for an explicit-component
     *  `startActivity` throws `ActivityNotFoundException`, a crash rather than the silent-drop
     *  failure mode a missing Commander produces). Checks [ApplicationInfo.enabled] on top of the
     *  installed check, not just presence — a user can disable an app from Android's own App Info
     *  screen (or `adb shell pm disable-user`) without uninstalling it, and `getApplicationInfo`
     *  happily returns that disabled app's info without throwing; a disabled app is exactly as
     *  unreachable for a broadcast/launch as an absent one, so treating it as "not installed" here
     *  is what every caller of this function actually needs. */
    fun isAppInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun discover(context: Context): List<VoxAppInfo> {
        val pm = context.packageManager
        val intent = Intent(VoxIpc.ACTION_COMMAND)
        return pm.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA)
            .mapNotNull { ri ->
                val info = ri.activityInfo ?: return@mapNotNull null
                if (info.packageName == context.packageName) return@mapNotNull null
                val md = info.metaData
                val label = md?.getString(VoxIpc.META_LABEL)
                    ?: info.applicationInfo?.loadLabel(pm)?.toString()
                    ?: info.packageName
                val domain = md?.getString(VoxIpc.META_DOMAIN)
                val actions = md?.getString(VoxIpc.META_ACTIONS)
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                val nluHint = md?.getString(VoxIpc.META_NLU_HINT)?.trim()?.takeIf { it.isNotEmpty() }
                VoxAppInfo(
                    packageName = info.packageName,
                    label = label,
                    domain = domain,
                    actions = actions,
                    isFirstParty = isFirstParty(pm, context.packageName, info.packageName),
                    nluHint = nluHint
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** OS-level signature check: is [target] signed with the same developer key as [self]? */
    private fun isFirstParty(pm: PackageManager, self: String, target: String): Boolean = try {
        pm.checkSignatures(self, target) == PackageManager.SIGNATURE_MATCH
    } catch (e: Exception) {
        false
    }

    /**
     * Live handshake: sends an ordered [VoxIpc.OP_PING] broadcast and waits for the satellite's
     * `ok` reply. Requires the caller to hold that satellite's COMMAND permission.
     */
    suspend fun ping(context: Context, packageName: String, timeoutMs: Long = 2_000L): Boolean {
        val intent = Intent(VoxIpc.ACTION_COMMAND).apply {
            setPackage(packageName)
            putExtra(VoxIpc.EXTRA_PAYLOAD, VoxCommand(op = VoxIpc.OP_PING).toJson())
            // Without this flag a force-stopped or never-since-installed app is dropped before its
            // receiver runs, no matter that setPackage() targets it — so it looks identical to an
            // app that is simply slow, and the only recovery was to launch its activity (see
            // BalGraceFlash) and ask again.
            //
            // Measured on a DNP-NX9 / Android 16 with Calendar and Notes force-stopped: with the
            // flag, both answered this ping directly, started, and completed a full export — no
            // flash sequence, no "open apps first" prompt. That matters most to
            // com.voxapps.hub.domain.backup.BackupWorker, which cannot launch anything at all and
            // was therefore skipping a force-stopped app from every scheduled run indefinitely.
            //
            // The flash fallback stays: this is one device, OEMs layer their own app-freeze
            // behaviour on top, and a stopped app is only one of the ways a broadcast goes
            // unanswered. It is now the exception rather than the normal path.
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                context.sendOrderedBroadcast(
                    intent,
                    null,
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context, i: Intent) {
                            val result = VoxResult.fromJson(resultData)
                            if (cont.isActive) cont.resume(result?.ok == true)
                        }
                    },
                    null,
                    0,
                    null,
                    null as Bundle?
                )
            }
        } ?: run {
            Logger.d("VoxAppsDiscovery", "Ping to $packageName timed out")
            false
        }
    }

    /**
     * Like [ping], but for callers (namely [com.voxapps.hub.domain.backup.BackupWorker]'s scheduled
     * run) that need to wait out a killed satellite's cold start rather than give up after one short
     * attempt. A single [ping] already cold-starts the target (explicit broadcast to a manifest
     * receiver survives background-broadcast limits), but a short timeout can fire before Hilt/Room/
     * WorkManager init on the *satellite's* side finishes — especially when several satellites are
     * cold-starting back-to-back in the same background run. Retries with a fresh ping each time
     * (each is a brand-new explicit broadcast, cheap to resend) until one succeeds or [totalTimeoutMs]
     * is exhausted, so a slow-but-eventually-successful wake-up is treated as reachable instead of
     * being silently skipped.
     */
    suspend fun pingUntilReady(
        context: Context,
        packageName: String,
        totalTimeoutMs: Long = 30_000L,
        attemptTimeoutMs: Long = 5_000L
    ): Boolean {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            if (ping(context, packageName, timeoutMs = minOf(attemptTimeoutMs, remaining))) return true
        }
    }
}
