package com.voxapps.commander.domain.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxSatelliteSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one satellite-initiated broadcast in this contract: a satellite fires this the instant its own
 * dynamic context (categories, currency, etc.) changes, so [VoxSatelliteRegistry]'s cache is corrected
 * immediately instead of staying wrong until the user presses Refresh. See the collapsed voice-command
 * plan's reasoning for why this is a deliberate, narrow exception to manual-only cache invalidation —
 * a precise, verified-event push, not a poll or timer.
 */
class SchemaChangedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_SCHEMA_CHANGED) return
        // A plain broadcast doesn't reliably expose the caller's identity via the Intent itself — the
        // sender states its own package explicitly, same reason VoxLlmRequest.sourcePackage exists.
        val sender = intent.getStringExtra(VoxIpc.EXTRA_SOURCE_PACKAGE) ?: return
        val appContext = context.applicationContext

        // Belt-and-suspenders alongside the manifest-level signature permission: refuse to trust a
        // push claiming to be from a package that isn't actually signed with our own key.
        val same = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.checkSignatures(appContext.packageName, sender) == PackageManager.SIGNATURE_MATCH
        } catch (e: Exception) {
            false
        }
        if (!same) {
            Logger.log("Refusing schema-changed push — signature mismatch for $sender", TAG)
            return
        }

        val schema = VoxSatelliteSchema.fromJson(intent.getStringExtra(VoxIpc.EXTRA_SCHEMA_PAYLOAD))
        if (schema == null) {
            Logger.log("Ignoring malformed schema-changed push from $sender", TAG)
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VoxSatelliteRegistry.applyPushedSchema(appContext, sender, schema)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SchemaChangedReceiver"
    }
}
