package com.voxcommander.app.domain.intent.resolver

import com.voxcommander.app.data.preferences.AppSettings
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.utils.Logger

/**
 * Resolves the target AppEntry for an NluIntent.
 *
 * Resolution chain:
 * 1. Explicit targetApp from the intent (package name lookup, if installed)
 * 2. User's default app for the domain (from SettingsRepository/DataStore)
 * 3. Domain default (first installed app in domain)
 * 4. null (system default / implicit intent)
 *
 * Returns the full AppEntry (with URI templates) or null for system default.
 */
object AppResolver {

    private const val TAG = "AppResolver"

    /**
     * Resolves the target app for an NluIntent.
     * @param intent The intent to resolve.
     * @param settings Current app settings snapshot (for user default app preferences). null = skip user defaults.
     * @return AppEntry if resolved, null for system default / implicit intent.
     */
    fun resolve(intent: NluIntent, settings: AppSettings? = null): AppRegistry.AppEntry? {
        // 1. Try explicit targetApp — first by package name, then by display name
        val explicit = AppRegistry.resolveByPackage(intent.targetApp)
            ?: AppRegistry.resolveByName(intent.targetApp)
        if (explicit != null) {
            Logger.log("Resolved '${intent.targetApp}' -> ${explicit.packageName} (EXPLICIT)", TAG)
            return explicit
        }

        // 1b. Try app alias rules (user-defined aliases, e.g. "youtube" -> LibreTube)
        if (settings != null && !intent.targetApp.isNullOrBlank()) {
            val lowerTarget = intent.targetApp.trim().lowercase()
            val aliasRule = settings.appAliasRules.firstOrNull { rule ->
                rule.enabled && lowerTarget in rule.aliases.map { it.lowercase() }
            }
            if (aliasRule != null) {
                val aliasApp = AppRegistry.resolveByPackage(aliasRule.packageName)
                if (aliasApp != null) {
                    Logger.log("Resolved '${intent.targetApp}' -> ${aliasApp.packageName} (ALIAS rule '${aliasRule.displayName}')", TAG)
                    return aliasApp
                }
            }
        }

        // 2. Try user's default app for this domain (from DataStore preferences)
        if (settings != null) {
            val userDefaultPkg = settings.defaultAppPackages[intent.domain]
            if (!userDefaultPkg.isNullOrBlank()) {
                val userDefault = AppRegistry.resolveByPackage(userDefaultPkg)
                if (userDefault != null) {
                    Logger.log("Using user default for '${intent.domain}' -> ${userDefault.packageName}", TAG)
                    return userDefault
                }
            }
        }

        // 2b. No star set — fall back to the first app the user assigned to this domain. Custom
        // categories have no probed default, so an assigned app acts as the implicit default.
        if (settings != null) {
            val assignedPkg = settings.domainAppPackages[intent.domain]?.firstOrNull { it.isNotBlank() }
            val assigned = assignedPkg?.let { AppRegistry.resolveByPackage(it) }
            if (assigned != null) {
                Logger.log("Using first assigned app for '${intent.domain}' -> ${assigned.packageName}", TAG)
                return assigned
            }
        }

        // 3. Try domain default (first installed app)
        val domainDefault = AppRegistry.getDefaultAppForDomain(intent.domain)
        if (domainDefault != null) {
            Logger.log("Using domain default for '${intent.domain}' -> ${domainDefault.packageName}", TAG)
            return domainDefault
        }

        // 4. System default / implicit
        Logger.log("No app found for domain='${intent.domain}', targetApp='${intent.targetApp}'. Using system default.", TAG)
        return null
    }

    /**
     * Resolves an app by explicit package name (used by FastMap rules with targetPackage).
     * @return AppEntry if installed, null otherwise.
     */
    fun resolveByPackage(packageName: String?): AppRegistry.AppEntry? {
        return AppRegistry.resolveByPackage(packageName)
    }
}
