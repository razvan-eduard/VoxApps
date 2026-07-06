package com.voxcommander.app.domain.intent.handler

import android.content.Context
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.domain.intent.registry.AppRegistry

/**
 * Strategy pattern interface for handling specific intent domains.
 * Each handler is responsible for executing a specific domain of intents
 * (audio, navigation, system settings, messaging, etc.).
 *
 * The resolved AppEntry (with URI templates) is passed by IntentRouter,
 * so handlers don't need to call AppResolver themselves.
 */
interface IntentHandler {

    /**
     * Returns true if this handler can process the given intent.
     * Typically checks the domain field.
     */
    fun canHandle(intent: NluIntent): Boolean

    /**
     * Executes the intent — launches the appropriate app or performs the action.
     * @param context Android context.
     * @param intent The parsed NLU intent.
     * @param resolvedApp The resolved target app (with URI templates), or null for system default.
     * @return true if execution succeeded, false otherwise.
     */
    fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean
}
