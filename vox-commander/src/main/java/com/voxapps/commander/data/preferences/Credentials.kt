package com.voxapps.commander.data.preferences

/**
 * The secrets held in encrypted storage, keyed by the engine that uses them.
 *
 * One key per engine, not one key per app. The engines that need a credential already say so — they
 * declare the `requires_api_key` capability in the schema — so who needs a key is data, and this is
 * simply the store shaped to match: a map, addressed by the same engine key everything else uses.
 *
 * It replaces three named fields, which forced two decisions nobody made deliberately. Cloud intent
 * parsing and cloud transcription are separate services billed separately, yet both read a single
 * `api_key`, so a key entered for one silently became the key for the other; and a fourth credential
 * (Porcupine's) sat outside the scheme entirely with a setter of its own. Anything an engine
 * declares now gets a slot, and two engines share a credential only if the user puts the same value
 * in both.
 *
 * Storage lives in `EncryptedSharedPreferences` under one namespace, managed like every other
 * setting: [SettingsRepository.credentialsFlow] to observe, [SettingsRepository.getCredentialsSnapshot]
 * to read, [SettingsRepository.setEngineApiKey] to write.
 */
data class Credentials(
    val byEngine: Map<String, String> = emptyMap(),
    /** The same store, for the search providers that own a key rather than borrowing an engine's.
     *  They are declared in a schema of their own and addressed by provider name, so they cannot
     *  share the engine map — but there is no reason for them to be read and written differently,
     *  which is what a separate synchronous accessor made them. */
    val bySearchProvider: Map<String, String> = emptyMap()
) {

    /** The credential for [engineKey], or null when it has none. Blank counts as none: an emptied
     *  text field must read as "not configured" rather than as a key that happens to be empty. */
    fun forEngine(engineKey: String): String? = byEngine[engineKey]?.takeIf { it.isNotBlank() }

    fun has(engineKey: String): Boolean = forEngine(engineKey) != null

    fun forSearchProvider(providerName: String): String? =
        bySearchProvider[providerName]?.takeIf { it.isNotBlank() }
}
