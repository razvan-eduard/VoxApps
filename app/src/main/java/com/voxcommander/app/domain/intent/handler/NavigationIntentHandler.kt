package com.voxcommander.app.domain.intent.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voxcommander.app.domain.intent.model.NluIntent
import com.voxcommander.app.domain.intent.registry.AppRegistry
import com.voxcommander.app.domain.intent.taxonomy.IntentTaxonomy
import com.voxcommander.app.utils.IntentUtils
import com.voxcommander.app.utils.Logger

/**
 * Handles navigation domain intents: navigate to a destination.
 * Uses URI templates from AppEntry for generic navigation deep links.
 * Supports any app registered in AppRegistry (Waze, Google Maps, etc.).
 */
class NavigationIntentHandler : IntentHandler {

    override fun canHandle(intent: NluIntent): Boolean {
        return intent.domain == IntentTaxonomy.Domains.MAPS
    }

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        // Check GPS before attempting navigation
        if (!SystemIntentHandler.ensureGpsEnabled(context)) {
            Logger.log("GPS is off — opened location settings for user to enable", TAG)
            return false
        }

        val destination = intent.logicalSubject
        if (destination.isNullOrBlank()) {
            Logger.log("Navigation intent missing destination parameter", TAG)
            return false
        }

        val pkg = resolvedApp?.packageName

        // Use URI template: intent.uriTemplate first, then resolvedApp.uriTemplates
        val navTemplate = intent.uriTemplate ?: resolvedApp?.uriTemplates?.get(AppRegistry.TemplateActions.NAVIGATE)
        if (navTemplate != null) {
            val uri = navTemplate.replace(AppRegistry.TemplateParams.DESTINATION, Uri.encode(destination))
            val navIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(uri)
                if (pkg != null) setPackage(pkg)
            }
            if (IntentUtils.tryLaunch(context, navIntent, TAG)) return true
        }

        // No template or template failed — try launching app with geo: intent
        if (pkg != null) {
            val launchIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
                setPackage(pkg)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (IntentUtils.tryLaunch(context, launchIntent, TAG)) return true
        }

        // Fallback: generic geo: intent (any maps app can handle)
        return IntentUtils.tryLaunch(context, Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }, TAG)
    }

    companion object {
        private const val TAG = "NavigationIntentHandler"
    }
}
