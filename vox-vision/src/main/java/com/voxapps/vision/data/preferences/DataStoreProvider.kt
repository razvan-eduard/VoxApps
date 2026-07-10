package com.voxapps.vision.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.visionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vox_vision_settings"
)

object DataStoreProvider {
    fun get(context: Context): DataStore<Preferences> = context.applicationContext.visionDataStore
}
