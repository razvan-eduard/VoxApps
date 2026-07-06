package com.voxapps.commander.domain.intent.model

import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NluIntentTest {

    @Test
    fun `logicalSubject is stored correctly`() {
        val intent = NluIntent(
            actionVerb = "play",
            logicalSubject = "Scorpions",
            domain = IntentTaxonomy.Domains.AUDIO,
            action = IntentTaxonomy.Actions.PLAY
        )
        assertEquals("Scorpions", intent.logicalSubject)
    }

    @Test
    fun `logicalSubject defaults to null`() {
        val intent = NluIntent(
            actionVerb = "volume",
            domain = IntentTaxonomy.Domains.SETTINGS,
            action = IntentTaxonomy.Actions.VOLUME_UP
        )
        assertNull(intent.logicalSubject)
    }

    @Test
    fun `default confidence is 1_0`() {
        val intent = NluIntent(
            actionVerb = "navigate",
            domain = IntentTaxonomy.Domains.MAPS,
            action = IntentTaxonomy.Actions.NAVIGATE
        )
        assertEquals(1.0f, intent.confidence)
    }

    @Test
    fun `default targetApp is null`() {
        val intent = NluIntent(
            actionVerb = "play",
            domain = IntentTaxonomy.Domains.AUDIO,
            action = IntentTaxonomy.Actions.PLAY
        )
        assertNull(intent.targetApp)
    }

    @Test
    fun `default modifiers and contextWords are empty lists`() {
        val intent = NluIntent(
            actionVerb = "play",
            domain = IntentTaxonomy.Domains.AUDIO,
            action = IntentTaxonomy.Actions.PLAY
        )
        assertTrue(intent.modifiers.isEmpty())
        assertTrue(intent.contextWords.isEmpty())
    }

    @Test
    fun `default extras is empty map`() {
        val intent = NluIntent(
            actionVerb = "play",
            domain = IntentTaxonomy.Domains.AUDIO,
            action = IntentTaxonomy.Actions.PLAY
        )
        assertTrue(intent.extras.isEmpty())
    }

    @Test
    fun `intentAction and uriTemplate are null by default`() {
        val intent = NluIntent(
            actionVerb = "play",
            domain = IntentTaxonomy.Domains.AUDIO,
            action = IntentTaxonomy.Actions.PLAY
        )
        assertNull(intent.intentAction)
        assertNull(intent.uriTemplate)
    }

    @Test
    fun `mediaControlType is null by default`() {
        val intent = NluIntent(
            actionVerb = "play",
            domain = IntentTaxonomy.Domains.AUDIO,
            action = IntentTaxonomy.Actions.PLAY
        )
        assertNull(intent.mediaControlType)
    }

    @Test
    fun `extras stores message_body correctly`() {
        val intent = NluIntent(
            actionVerb = "send",
            logicalSubject = "maria",
            domain = IntentTaxonomy.Domains.MESSAGING,
            action = IntentTaxonomy.Actions.SEND,
            extras = mapOf(NluIntent.EXTRA_MESSAGE_BODY to "Hello")
        )
        assertEquals("Hello", intent.extras[NluIntent.EXTRA_MESSAGE_BODY])
    }
}
