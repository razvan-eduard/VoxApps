package com.voxapps.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one place a preferences [DataStore] is created, for every app in the repo.
 *
 * Each app previously carried its own `DataStoreProvider` — six files differing only in package
 * name, the name of a private `Context` extension property, and the store name string. The usual
 * `by preferencesDataStore(name = ...)` delegate can't be shared, because the delegate *is* the
 * per-store singleton: it has to be declared once per store, at file scope, with the name fixed at
 * compile time. Taking the name as a parameter means holding the instances here instead.
 *
 * DataStore enforces that a given file is backed by at most one instance per process and throws if
 * that is violated, so the caching below is not an optimisation — it is the invariant the delegate
 * form used to provide structurally. [stores] is guarded because [get] is reachable from whichever
 * thread first touches settings (an app's `init`, a widget's update, a headless IPC receiver), and
 * two racing callers must not each build one.
 */
object VoxDataStore {
    private val stores = HashMap<String, DataStore<Preferences>>()

    /** Matches the delegate's own scope: IO for the file work, SupervisorJob so one store's failure
     *  can't cancel another's. Process-lifetime by design — these are never disposed. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * @param name the store's file name, without extension — e.g. `"vox_notes_settings"`. Must stay
     *   stable across releases: it is the on-disk identity of the user's settings.
     */
    fun get(context: Context, name: String): DataStore<Preferences> {
        val appContext = context.applicationContext
        return synchronized(stores) {
            stores.getOrPut(name) {
                PreferenceDataStoreFactory.create(scope = scope) {
                    appContext.preferencesDataStoreFile(name)
                }
            }
        }
    }
}
