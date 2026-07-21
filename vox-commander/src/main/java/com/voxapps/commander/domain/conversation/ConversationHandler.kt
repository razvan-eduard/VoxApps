package com.voxapps.commander.domain.conversation

import com.voxapps.commander.domain.voice.TtsManager
import com.voxapps.commander.state.VoiceState
import com.voxapps.commander.state.AppStateManager
import com.voxapps.logging.Logger
import com.voxapps.commander.utils.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Coordinates the conversation pipeline: STT → LLM → TTS with barge-in support.
 *
 * Uses [ConversationStateMachine] to track the current phase.
 * During SPEAKING, the wake word engine stays active so the user can
 * interrupt (barge-in) by saying the wake word.
 *
 * Singleton — initialized once from [AppContainer].
 */
object ConversationHandler {

    private const val TAG = "ConversationHandler"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateMachine = ConversationStateMachine()

    private var appStateManager: AppStateManager? = null
    private var initialized = false

    /**
     * Initializes the handler. Safe to call multiple times.
     */
    fun init(appStateManager: AppStateManager) {
        if (initialized) return
        this.appStateManager = appStateManager
        initialized = true
        Logger.log("ConversationHandler initialized", TAG)
    }

    /**
     * The current conversation state.
     */
    val state: ConversationState get() = stateMachine.state

    /**
     * Called when the user's command has been transcribed and is about
     * to be processed by the LLM / intent router.
     */
    fun onProcessingStarted() {
        stateMachine.transitionTo(ConversationState.PROCESSING)
    }

    /**
     * Speaks a response via TtsManager and transitions through the
     * SPEAKING → IDLE lifecycle.
     *
     * If barge-in occurs during SPEAKING, TTS is stopped and the state
     * transitions to BARGE_IN → LISTENING_COMMAND (handled by [handleBargeIn]).
     */
    fun speakResponse(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            stateMachine.reset()
            onComplete?.invoke()
            return
        }

        stateMachine.transitionTo(ConversationState.SPEAKING)
        appStateManager?.setVoiceState(VoiceState.PROCESSING)

        TtsManager.speak(text) {
            // TTS finished (or was interrupted by barge-in)
            if (stateMachine.state == ConversationState.SPEAKING) {
                stateMachine.transitionTo(ConversationState.IDLE)
                appStateManager?.setVoiceState(VoiceState.IDLE)
            }
            onComplete?.invoke()
        }
    }

    /**
     * Called when the wake word is detected during TTS playback (barge-in).
     * Stops TTS immediately and transitions to BARGE_IN, which should be
     * followed by LISTENING_COMMAND for the next user command.
     *
     * Returns true if barge-in was handled (i.e., TTS was speaking),
     * false if TTS was not active (normal wake word flow).
     */
    fun handleBargeIn(): Boolean {
        if (stateMachine.state != ConversationState.SPEAKING) {
            return false
        }

        Logger.log("Barge-in! Stopping TTS playback", TAG)
        TtsManager.stop()
        stateMachine.transitionTo(ConversationState.IDLE)
        // Don't set voiceState here — the background trigger in WakeWordService
        // collects wakeWordEvents and will call processVoiceCommand.
        // Setting voiceState=IDLE would trigger handleVoiceStateChange(IDLE)
        // which restarts wake word engine and can cause false detections → loops.
        // We need voiceState to NOT be PROCESSING so the trigger calls processVoiceCommand
        // instead of enqueuing. Use LISTENING_COMMAND as a transient state.
        appStateManager?.setVoiceState(VoiceState.LISTENING_COMMAND)
        return true
    }

    /**
     * Transitions from BARGE_IN to LISTENING_COMMAND.
     * Called after barge-in when the system is ready to listen
     * for the next command.
     */
    fun onBargeInComplete() {
        if (stateMachine.state == ConversationState.BARGE_IN) {
            stateMachine.transitionTo(ConversationState.LISTENING_COMMAND)
        }
    }

    /**
     * Resets the conversation state to IDLE.
     */
    fun reset() {
        stateMachine.reset()
    }
}
