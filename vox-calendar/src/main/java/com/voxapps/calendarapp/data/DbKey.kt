package com.voxapps.calendarapp.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Generates a random 32-byte DB passphrase once and stores it in EncryptedSharedPreferences,
 * whose master key lives in the Android Keystore (hardware-backed where available). The passphrase
 * itself never leaves the device and is never stored in plaintext.
 */
object DbKey {
    private const val PREFS = "vox-calendar-secure"
    private const val KEY = "db_passphrase_b64"

    fun getOrCreatePassphrase(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }

        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(passphrase, Base64.NO_WRAP)).apply()
        return passphrase
    }
}
