package com.voxapps.commander.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxapps.commander.domain.intent.interpreter.AssistantEngine
import com.voxapps.commander.domain.intent.model.NluIntent
import com.voxapps.commander.domain.intent.router.IntentRouter
import com.voxapps.commander.domain.localization.LanguageManager
import com.voxapps.commander.domain.voice.VoiceManager
import com.voxapps.commander.state.AppStateManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.voxapps.commander.domain.search.SearchResultsHolder
import com.voxapps.commander.state.VoiceState
import com.voxapps.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val assistantEngine: AssistantEngine,
    private val intentRouter: IntentRouter,
    private val appStateManager: AppStateManager,
    private val languageManager: LanguageManager
) : ViewModel() {

    private val TAG = "MainViewModel"
    private val _currentIntent = MutableStateFlow<NluIntent?>(null)
    val currentIntent = _currentIntent.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription = _transcription.asStateFlow()

    val searchResults = SearchResultsHolder.searchResults

    private val commandQueue = mutableListOf<Pair<String, String>>()
    private val queueLock = Any()

    fun processVoiceCommand(modelFilterLang: String, userPreference: String) {
        _isProcessing.value = true
        VoiceManager.startListening(modelFilterLang, userPreference) { text ->
            val cleanText = text.trim()
            _transcription.value = cleanText
            
            // The engines report failure as an "Error: …" transcript — the shared predicate is
            // the one place that convention is read, so an error never gets parsed as a command.
            if (!VoiceManager.isUsableTranscript(cleanText)) {
                _isProcessing.value = false
                appStateManager.setVoiceState(VoiceState.IDLE)
                return@startListening
            }

            viewModelScope.launch {
                try {
                    appStateManager.setVoiceState(VoiceState.PROCESSING)
                    val result = assistantEngine.processCommand(cleanText, modelFilterLang)
                    _currentIntent.value = result
                    result?.let { withContext(Dispatchers.IO) { intentRouter.route(it) } }
                } catch (e: Exception) {
                    Logger.log("Voice command processing failed: ${e.message}", TAG)
                } finally {
                    drainQueueOrIdle(modelFilterLang)
                }
            }
        }
    }

    fun enqueueVoiceCommand(modelFilterLang: String, userPreference: String) {
        Logger.log("Queuing voice command — recording while processing", TAG)
        VoiceManager.startListening(modelFilterLang, userPreference) { text ->
            val cleanText = text.trim()
            if (VoiceManager.isUsableTranscript(cleanText)) {
                synchronized(queueLock) {
                    commandQueue.add(Pair(cleanText, modelFilterLang))
                    Logger.log("Command queued: '$cleanText' (queue size: ${commandQueue.size})", TAG)
                }
            }
        }
    }

    private fun drainQueueOrIdle(modelFilterLang: String) {
        val next = synchronized(queueLock) {
            if (commandQueue.isEmpty()) null else commandQueue.removeAt(0)
        }

        if (next != null) {
            val (queuedText, queuedLang) = next
            Logger.log("Processing queued command: '$queuedText'", TAG)
            _transcription.value = queuedText
            viewModelScope.launch {
                try {
                    appStateManager.setVoiceState(VoiceState.PROCESSING)
                    val result = assistantEngine.processCommand(queuedText, queuedLang)
                    _currentIntent.value = result
                    result?.let { withContext(Dispatchers.IO) { intentRouter.route(it) } }
                } catch (e: Exception) {
                    Logger.log("Queued command processing failed: ${e.message}", TAG)
                } finally {
                    drainQueueOrIdle(queuedLang)
                }
            }
        } else {
            _isProcessing.value = false
            appStateManager.setVoiceState(VoiceState.IDLE)
        }
    }

    fun stopVoiceCommand() {
        VoiceManager.stopListening()
        com.voxapps.commander.domain.voice.TtsManager.stop()
        _isProcessing.value = false
        appStateManager.setVoiceState(VoiceState.IDLE)
    }

    fun processTextCommand(text: String, modelFilterLang: String? = null) {
        _transcription.value = text
        viewModelScope.launch {
            _isProcessing.value = true
            appStateManager.setVoiceState(VoiceState.PROCESSING)
            try {
                val result = assistantEngine.processCommand(text, modelFilterLang)
                _currentIntent.value = result
                result?.let { withContext(Dispatchers.IO) { intentRouter.route(it) } }
            } catch (e: Exception) {
                Logger.log("Text command processing failed: ${e.message}", TAG)
            } finally {
                _isProcessing.value = false
                appStateManager.setVoiceState(VoiceState.IDLE)
            }
        }
    }

    fun routeManualIntent(json: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            appStateManager.setVoiceState(VoiceState.PROCESSING)
            try {
                val gson = Gson()
                val intent = gson.fromJson(json, NluIntent::class.java)
                if (intent != null) {
                    _currentIntent.value = intent
                    Logger.log("Manual intent routed: $json", TAG)
                    withContext(Dispatchers.IO) { intentRouter.route(intent) }
                }
            } catch (e: Exception) {
                Logger.log("Manual intent parse error: ${e.message}", TAG)
            } finally {
                _isProcessing.value = false
                appStateManager.setVoiceState(VoiceState.IDLE)
            }
        }
    }
}
