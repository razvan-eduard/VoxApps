package com.voxapps.recordflow.ui

import com.voxapps.recordflow.AskScope
import com.voxapps.recordflow.FieldWeight
import com.voxapps.recordflow.FlowSupport
import com.voxapps.recordflow.LlmLevel
import com.voxapps.recordflow.RecordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the two controls actually do to the stored rung.
 *
 * The card asks two questions and stores one value, so the whole risk lives in the translation
 * between them: a screen that can reach a rung the satellite never declared, or that quietly changes
 * how much is written when the user only changed how much is sent, is wrong in a way no amount of
 * looking at the screen reveals.
 */
class RecordFlowLevelCardTest {

    private val everything = FlowSupport(
        RecordSource.SCAN,
        supported = LlmLevel.entries.toSet(),
        default = LlmLevel.FULL,
        suggestsAnswers = true
    )

    /** A satellite with nowhere to show a proposal: only the rungs that write what they asked for. */
    private val noSurface = FlowSupport(
        RecordSource.VOICE,
        supported = setOf(LlmLevel.NONE, LlmLevel.ASSIST_AUTO, LlmLevel.HEAD_AUTO, LlmLevel.FULL),
        default = LlmLevel.FULL
    )

    @Test
    fun `widening what is sent keeps what is written`() {
        // Filling in the coarse fields survives a move from the narrow question to the wide one.
        assertEquals(
            LlmLevel.HEAD_AUTO,
            everything.settle(AskScope.ALL_HEAD, from = LlmLevel.ASSIST_AUTO)
        )
        // And so does writing nothing.
        assertEquals(
            LlmLevel.HEAD_SUGGEST,
            everything.settle(AskScope.ALL_HEAD, from = LlmLevel.ASSIST_SUGGEST)
        )
    }

    /** Narrowing cannot keep a promise the new scope never asks about, so it drops what it must. */
    @Test
    fun `narrowing what is sent drops only what the new scope cannot cover`() {
        assertEquals(
            LlmLevel.HEAD_AUTO,
            everything.settle(AskScope.ALL_HEAD, from = LlmLevel.FULL)
        )
        assertEquals(
            LlmLevel.NONE,
            everything.settle(AskScope.NOTHING, from = LlmLevel.FULL)
        )
    }

    /** Every scope reachable on a fully capable satellite lands somewhere, and never outside what it
     *  declared. */
    @Test
    fun `settling always lands on a supported rung`() {
        listOf(everything, noSurface).forEach { support ->
            val scopes = AskScope.entries.filter { s -> support.supported.any { it.asks == s } }
            LlmLevel.entries.filter { it in support.supported }.forEach { from ->
                scopes.forEach { scope ->
                    val landed = support.settle(scope, from)
                    assertTrue("$from -> $scope left the contract", landed in support.supported)
                    assertEquals("$from -> $scope changed the scope", scope, landed?.asks)
                }
            }
        }
    }

    @Test
    fun `a box flips to the rung with that half written`() {
        assertEquals(
            LlmLevel.BODY_SUGGEST,
            everything.toggled(LlmLevel.ALL_SUGGEST, FieldWeight.HEAD)
        )
        assertEquals(
            LlmLevel.FULL,
            everything.toggled(LlmLevel.BODY_SUGGEST, FieldWeight.BODY)
        )
        assertEquals(
            LlmLevel.ALL_SUGGEST,
            everything.toggled(LlmLevel.BODY_SUGGEST, FieldWeight.HEAD)
        )
    }

    /**
     * Clearing the coarse box takes the fine one with it, rather than refusing the tap. Writing the
     * fine detail while leaving the coarse fields to be approved is the combination that means
     * nothing, and a box that looks available but does nothing is worse than one that carries the
     * consequence through.
     */
    @Test
    fun `clearing the coarse box clears the fine one with it`() {
        assertEquals(LlmLevel.ALL_SUGGEST, everything.toggled(LlmLevel.FULL, FieldWeight.HEAD))
    }

    /** And ticking the fine one implies the coarse one, from the same argument. */
    @Test
    fun `ticking the fine box implies the coarse one`() {
        assertEquals(LlmLevel.FULL, everything.toggled(LlmLevel.ALL_SUGGEST, FieldWeight.BODY))
    }

    /** Whatever the boxes do, they never land off the ladder. */
    @Test
    fun `toggling always lands on a real rung or refuses`() {
        listOf(everything, noSurface).forEach { support ->
            support.supported.forEach { level ->
                FieldWeight.entries.forEach { weight ->
                    val landed = support.toggled(level, weight)
                    if (landed != null) {
                        assertTrue("$level +$weight left the contract", landed in support.supported)
                        assertEquals("$level +$weight changed the scope", level.asks, landed.asks)
                    }
                }
            }
        }
    }

    /** With nowhere to show a proposal, a box is fixed on: the only honest thing to do with an
     *  answer is write it. */
    @Test
    fun `a satellite with no surface cannot clear a box`() {
        assertNull(noSurface.toggled(LlmLevel.HEAD_AUTO, FieldWeight.HEAD))
        assertNull(noSurface.toggled(LlmLevel.FULL, FieldWeight.BODY))
    }
}
