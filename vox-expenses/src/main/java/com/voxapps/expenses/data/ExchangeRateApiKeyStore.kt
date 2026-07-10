package com.voxapps.expenses.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for the user-supplied exchangerate-api.com API key (see `external_services.json`).
 * Mirrors vox-commander's `encryptedPrefs.getString("api_key", ...)` convention for third-party API
 * keys — a real secret, so it lives in Keystore-backed EncryptedSharedPreferences, never in the plain
 * DataStore-backed [com.voxapps.expenses.data.preferences.ExpensesSettings]. Kept in its own prefs
 * file (not [DbKey]'s) so this unrelated secret can't be confused with the DB passphrase.
 */
object ExchangeRateApiKeyStore {
    private const val PREFS = "vox-expenses-api-keys"
    private const val KEY_EXCHANGE_RATE_API_KEY = "exchange_rate_api_key"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(context: Context): String? = prefs(context).getString(KEY_EXCHANGE_RATE_API_KEY, null)

    fun set(context: Context, apiKey: String?) {
        prefs(context).edit().putString(KEY_EXCHANGE_RATE_API_KEY, apiKey).apply()
    }
}
