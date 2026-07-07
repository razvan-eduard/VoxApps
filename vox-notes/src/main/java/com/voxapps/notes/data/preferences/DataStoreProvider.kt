package com.voxapps.notes.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.notesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vox_notes_settings"
)

object DataStoreProvider {
    fun get(context: Context): DataStore<Preferences> = context.applicationContext.notesDataStore
}
