package com.voxapps.commander.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.voxapps.preferences.VoxDataStore

/**
 * This app's settings store. Only the name lives here now; the instance-per-file caching that every
 * app used to re-implement is in [VoxDataStore].
 *
 * STORE_NAME is the on-disk identity of the user's settings and must never change.
 */
object DataStoreProvider {
    private const val STORE_NAME = "vox_commander_settings"

    fun get(context: Context): DataStore<Preferences> = VoxDataStore.get(context, STORE_NAME)
}
