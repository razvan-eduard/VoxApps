package com.voxapps.hub.domain.sync

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether a background component may attempt a Bluetooth sync right now. Deliberately can't prompt
 * for anything (no Bluetooth-enable dialog, no runtime permission request) — background work has no
 * Activity to show one from — so an unmet prerequisite means "skip this attempt", retried whenever
 * the caller next runs.
 */
internal object SyncPrerequisites {
    fun bluetoothReady(context: Context): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        val connectPermission =
            if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ContextCompat.checkSelfPermission(context, connectPermission) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            false
        }
    }
}
