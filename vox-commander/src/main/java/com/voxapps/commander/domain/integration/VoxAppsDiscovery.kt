package com.voxapps.commander.domain.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.voxapps.commander.utils.Logger
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
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
    /** True if signed with Commander's own key — a "first-party" satellite that wins routing ties. */
    val isFirstParty: Boolean = false
)

/**
 * Discovers installed apps that implement the Vox contract (an exported [VoxIpc.ACTION_COMMAND]
 * receiver) and reads their advertised capability from manifest meta-data — no per-app config on
 * Commander. A user's own app that declares the contract self-registers here. [ping] is the live
 * "does it actually respond" check.
 */
object VoxAppsDiscovery {

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
                VoxAppInfo(info.packageName, label, domain, actions, isFirstParty(pm, context.packageName, info.packageName))
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
     * `ok` reply. Requires Commander to hold that satellite's COMMAND permission.
     */
    suspend fun ping(context: Context, packageName: String, timeoutMs: Long = 2_000L): Boolean {
        val intent = Intent(VoxIpc.ACTION_COMMAND).apply {
            setPackage(packageName)
            putExtra(VoxIpc.EXTRA_PAYLOAD, VoxCommand(op = VoxIpc.OP_PING).toJson())
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
            Logger.log("Ping to $packageName timed out", "VoxAppsDiscovery")
            false
        }
    }
}
