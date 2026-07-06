package com.voxcommander.app.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Wrapper for Google Voice Intent launcher.
 * Encapsulates the ActivityResultLauncher and intent creation logic.
 */
class VoiceIntentLauncher(
    private val activity: ComponentActivity,
    private val onResult: (String) -> Unit
) {
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = matches?.firstOrNull() ?: ""
            Logger.log("Heard via Intent: $recognizedText", Strings.Tags.VOX_COMMANDER)
            onResult(recognizedText)
        } else {
            // Handle cancellation or error to stop the infinite recording state
            onResult("")
        }
    }

    /**
     * Launches the Google Voice Recognition intent.
     * @param langCode Language code (e.g., "ro-RO", "en-US")
     */
    fun launch(langCode: String = "ro-RO") {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening for command...")
        }

        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                activity,
                "Google App is not installed on this device!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
