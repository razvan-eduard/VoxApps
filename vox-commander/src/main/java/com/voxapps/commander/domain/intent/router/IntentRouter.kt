package com.voxapps.commander.domain.intent.router

import android.app.ActivityManager
import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.domain.intent.handler.AudioIntentHandler
import com.voxapps.commander.domain.intent.handler.GenericLaunchHandler
import com.voxapps.commander.domain.intent.handler.IntentHandler
import com.voxapps.commander.domain.intent.handler.MessagingIntentHandler
import com.voxapps.commander.domain.intent.handler.NavigationIntentHandler
import com.voxapps.commander.domain.intent.handler.SearchIntentHandler
import com.voxapps.commander.domain.intent.handler.SystemIntentHandler
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.resolver.AppResolver
import com.voxapps.commander.utils.AppScope
import com.voxapps.commander.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        SearchIntentHandler(settingsRepository),
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

                val targetPkg = resolvedApp?.packageName
                val shouldReturnAfter = targetPkg != null && targetPkg in settings.returnAfterActionApps
                val previousApp = if (shouldReturnAfter) getForegroundPackage() else null

                val success = handler.execute(context, intent, resolvedApp)
                Logger.log("Handler ${handler::class.simpleName} result: $success", TAG)

                if (shouldReturnAfter && previousApp != null && previousApp != targetPkg) {
                    Logger.log("Return-to-previous enabled for $targetPkg, will return to $previousApp after delay", TAG)
                    AppScope.main.launch {
                        delay(1500)
                        returnToApp(previousApp)
                    }
                }

                return success
            }
        }

        Logger.log("No handler found for domain=${intent.domain}", TAG)
        return false
    }

    private fun getForegroundPackage(): String? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.runningAppProcesses
            info?.firstOrNull { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }?.processName
        } catch (e: Exception) {
            null
        }
    }

    private fun returnToApp(packageName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                context.startActivity(launchIntent)
                Logger.log("Returned to previous app: $packageName", TAG)
            }
        } catch (e: Exception) {
            Logger.log("Failed to return to $packageName: ${e.message}", TAG)
        }
    }

    companion object {
        private const val TAG = "IntentRouter"
    }
}
