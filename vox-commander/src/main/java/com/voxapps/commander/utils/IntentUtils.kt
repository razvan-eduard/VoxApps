package com.voxapps.commander.utils

import com.voxapps.logging.Logger

import android.content.Context
import android.content.Intent

object IntentUtils {

    fun tryLaunch(context: Context, intent: Intent, tag: String = "IntentUtils"): Boolean {
        return try {
            intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Logger.log("Failed to launch intent: ${e.message}", tag)
            false
        }
    }
}
