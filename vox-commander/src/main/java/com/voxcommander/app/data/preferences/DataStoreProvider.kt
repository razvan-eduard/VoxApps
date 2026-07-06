package com.voxcommander.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

private val Context.voxDataStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(
    name = "vox_commander_settings"
)

object DataStoreProvider {
    fun get(context: Context): DataStore<Preferences> = context.applicationContext.voxDataStore
}
