package com.voxcommander.app.domain.conversation

import android.util.Log
import com.voxcommander.app.utils.Logger
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ConversationStateMachine] — the valid-transition matrix, rejection of
 * invalid transitions, listener notification, and reset.
 */
class ConversationStateMachineTest {

    private lateinit var sm: ConversationStateMachine

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        mockkObject(Logger)
        every { Logger.log(any(), any()) } returns Unit
        sm = ConversationStateMachine()
    }

    @Test
    fun `starts in IDLE`() {
        assertEquals(ConversationState.IDLE, sm.state)
    }

    @Test
    fun `happy path IDLE to SPEAKING and back`() {
        sm.transitionTo(ConversationState.LISTENING_COMMAND)
        assertEquals(ConversationState.LISTENING_COMMAND, sm.state)
        sm.transitionTo(ConversationState.PROCESSING)
        assertEquals(ConversationState.PROCESSING, sm.state)
        sm.transitionTo(ConversationState.SPEAKING)
        assertEquals(ConversationState.SPEAKING, sm.state)
        sm.transitionTo(ConversationState.IDLE)
        assertEquals(ConversationState.IDLE, sm.state)
    }

    @Test
    fun `barge-in path from SPEAKING`() {
        sm.transitionTo(ConversationState.SPEAKING) // IDLE -> SPEAKING is valid
        sm.transitionTo(ConversationState.BARGE_IN)
        assertEquals(ConversationState.BARGE_IN, sm.state)
        sm.transitionTo(ConversationState.LISTENING_COMMAND)
        assertEquals(ConversationState.LISTENING_COMMAND, sm.state)
    }

    @Test
    fun `invalid transitions are ignored`() {
        // IDLE -> PROCESSING is not allowed
        sm.transitionTo(ConversationState.PROCESSING)
        assertEquals(ConversationState.IDLE, sm.state)
        // LISTENING_COMMAND -> SPEAKING is not allowed
        sm.transitionTo(ConversationState.LISTENING_COMMAND)
        sm.transitionTo(ConversationState.SPEAKING)
        assertEquals(ConversationState.LISTENING_COMMAND, sm.state)
    }

    @Test
    fun `listener fires on valid transition only`() {
        val seen = mutableListOf<ConversationState>()
        sm.onStateChange { seen.add(it) }

        sm.transitionTo(ConversationState.LISTENING_COMMAND) // valid
        sm.transitionTo(ConversationState.SPEAKING)          // invalid from LISTENING_COMMAND

        assertEquals(listOf(ConversationState.LISTENING_COMMAND), seen)
    }

    @Test
    fun `reset returns to IDLE from a non-idle state`() {
        sm.transitionTo(ConversationState.SPEAKING)
        sm.reset()
        assertEquals(ConversationState.IDLE, sm.state)
    }
}
