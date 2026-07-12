package com.voxapps.calendarapp.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.calendarDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vox_calendar_settings"
)

object DataStoreProvider {
    fun get(context: Context): DataStore<Preferences> = context.applicationContext.calendarDataStore
}
