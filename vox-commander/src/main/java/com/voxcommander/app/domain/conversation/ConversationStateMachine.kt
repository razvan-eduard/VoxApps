package com.voxcommander.app.domain.conversation

import com.voxcommander.app.utils.Logger

/**
 * States for the conversation pipeline (STT → LLM → TTS with barge-in).
 *
 * State transitions:
 *   IDLE → [wake word] → LISTENING_COMMAND → PROCESSING → SPEAKING → IDLE
 *                                    ↑           |           |
 *                              [barge-in]  [barge-in]  [barge-in] → LISTENING_COMMAND
 *
 * During SPEAKING, the wake word engine stays active.
 * If the user says the wake word, barge-in fires: TTS stops and we
 * transition back to LISTENING_COMMAND.
 */
enum class ConversationState {
    IDLE,               // No active conversation
    LISTENING_COMMAND,  // Recording user's command via STT
    PROCESSING,         // LLM is generating a response
    SPEAKING,           // TTS is playing back the response
    BARGE_IN            // Wake word detected during SPEAKING — interrupting
}

/**
 * Simple state machine for conversation flow.
 * Thread-safe via synchronized blocks.
 */
class ConversationStateMachine {

    companion object {
        private const val TAG = "ConversationStateMachine"
    }

    @Volatile
    private var _state: ConversationState = ConversationState.IDLE
    val state: ConversationState get() = _state

    private val listeners = mutableListOf<(ConversationState) -> Unit>()

    @Synchronized
    fun transitionTo(newState: ConversationState) {
        val oldState = _state
        if (!isValidTransition(oldState, newState)) {
            Logger.log("Invalid conversation transition: $oldState → $newState (ignored)", TAG)
            return
        }
        _state = newState
        Logger.log("Conversation state: $oldState → $newState", TAG)
        listeners.forEach { it(newState) }
    }

    fun onStateChange(listener: (ConversationState) -> Unit) {
        synchronized(this) {
            listeners.add(listener)
        }
    }

    fun reset() {
        transitionTo(ConversationState.IDLE)
    }

    /**
     * Returns true if the transition from [from] to [to] is valid.
     */
    private fun isValidTransition(from: ConversationState, to: ConversationState): Boolean {
        return when (from) {
            ConversationState.IDLE -> to == ConversationState.LISTENING_COMMAND || to == ConversationState.SPEAKING
            ConversationState.LISTENING_COMMAND -> to == ConversationState.PROCESSING || to == ConversationState.IDLE
            ConversationState.PROCESSING -> to == ConversationState.SPEAKING || to == ConversationState.IDLE
            ConversationState.SPEAKING -> to == ConversationState.IDLE || to == ConversationState.BARGE_IN
            ConversationState.BARGE_IN -> to == ConversationState.LISTENING_COMMAND || to == ConversationState.IDLE
        }
    }
}
