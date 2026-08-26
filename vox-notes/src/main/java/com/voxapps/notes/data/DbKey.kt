package com.voxapps.notes.data

import android.content.Context
import com.voxapps.preferences.VoxDbKey

/**
 * This app's database passphrase, from :core:preferences' shared Keystore-backed store logic.
 * PREFS is the on-disk identity of the secure store and must never change.
 */
object DbKey {
    private const val PREFS = "vox-notes-secure"

    fun getOrCreatePassphrase(context: Context): ByteArray =
        VoxDbKey.getOrCreatePassphrase(context, PREFS)
}
