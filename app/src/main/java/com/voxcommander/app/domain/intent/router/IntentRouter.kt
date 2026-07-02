package com.voxcommander.app.domain.intent.router

import android.content.Context
import com.voxcommander.app.data.preferences.SettingsRepository
import com.voxcommander.app.domain.intent.handler.AudioIntentHandler
import com.voxcommander.app.domain.intent.handler.GenericLaunchHandler
import com.voxcommander.app.domain.intent.handler.IntentHandler
import com.voxcommander.app.domain.intent.handler.MessagingIntentHandler
import com.voxcommander.app.domain.intent.handler.NavigationIntentHandler
import com.voxcommander.app.domain.intent.handler.SearchIntentHandler
import com.voxcommander.app.domain.intent.handler.SystemIntentHandler
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.domain.intent.resolver.AppResolver
import com.voxcommander.app.utils.Logger

/**
 * Central dispatcher for NluIntent execution.
 * Resolves the target app using AppResolver (with user preferences from SettingsRepository),
 * then delegates to the first registered IntentHandler that canHandle() the intent.
 */
class IntentRouter(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {

    private val handlers: List<IntentHandler> = listOf(
        SearchIntentHandler(),
        AudioIntentHandler(),
        NavigationIntentHandler(),
        SystemIntentHandler(),
        MessagingIntentHandler(),
        GenericLaunchHandler()
    )

    /**
     * Routes the intent to the appropriate handler and executes it.
     * @return true if a handler was found and execution succeeded, false otherwise.
     */
    fun route(intent: NluIntent): Boolean {
        Logger.log("Routing intent: domain=${intent.domain}, action=${intent.action}, verb=${intent.actionVerb}, subject=${intent.logicalSubject}, targetApp=${intent.targetApp}", TAG)

        val settings = settingsRepository.getSettingsSnapshot()
        val resolvedApp = AppResolver.resolve(intent, settings)

        for (handler in handlers) {
            if (handler.canHandle(intent)) {
                Logger.log("Handler ${handler::class.simpleName} accepted intent, resolvedApp=${resolvedApp?.packageName}", TAG)
                val success = handler.execute(context, intent, resolvedApp)
                Logger.log("Handler ${handler::class.simpleName} result: $success", TAG)
                return success
            }
        }

        Logger.log("No handler found for domain=${intent.domain}", TAG)
        return false
    }

    companion object {
        private const val TAG = "IntentRouter"
    }
}
