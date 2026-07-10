package com.voxapps.expenses.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.expensesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vox_expenses_settings"
)

object DataStoreProvider {
    fun get(context: Context): DataStore<Preferences> = context.applicationContext.expensesDataStore
}
