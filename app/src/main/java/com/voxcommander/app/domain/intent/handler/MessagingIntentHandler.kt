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
 * Handles messaging domain intents: send messages via WhatsApp, Telegram, Gmail, etc.
 * Uses URI templates from AppEntry when available (e.g. WhatsApp wa.me links).
 * Falls back to ACTION_SEND for apps without URI templates.
 */
class MessagingIntentHandler : IntentHandler {

    override fun canHandle(intent: NluIntent): Boolean {
        return intent.domain == IntentTaxonomy.Domains.MESSAGING
    }

    override fun execute(context: Context, intent: NluIntent, resolvedApp: AppRegistry.AppEntry?): Boolean {
        if (intent.action != IntentTaxonomy.Actions.SEND) {
            Logger.log("Unsupported messaging action: ${intent.action}", TAG)
            return false
        }

        val pkg = resolvedApp?.packageName
        val contact = intent.logicalSubject
        val messageBody = intent.extras[NluIntent.EXTRA_MESSAGE_BODY]

        // Use URI template: intent.uriTemplate first, then resolvedApp.uriTemplates
        val sendTemplate = intent.uriTemplate ?: resolvedApp?.uriTemplates?.get(AppRegistry.TemplateActions.SEND)
        if (sendTemplate != null && pkg != null) {
            val contactValue = contact?.replace(Regex("[^0-9]"), "") ?: ""
            val uri = sendTemplate.replace(AppRegistry.TemplateParams.CONTACT, contactValue)
            val sendIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(uri)
                setPackage(pkg)
                if (!messageBody.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, messageBody)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (IntentUtils.tryLaunch(context, sendIntent, TAG)) return true
        }

        // No template — try ACTION_SEND with the target package
        if (pkg != null) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                if (!contact.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(contact))
                if (!messageBody.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, messageBody)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (IntentUtils.tryLaunch(context, sendIntent, TAG)) return true
        }

        // Fallback: generic share intent
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (!contact.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(contact))
            if (!messageBody.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, messageBody)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return IntentUtils.tryLaunch(context, shareIntent, TAG)
    }

    companion object {
        private const val TAG = "MessagingIntentHandler"
    }
}
