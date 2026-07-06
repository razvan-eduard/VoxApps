package com.voxcommander.app.domain.intent.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentTaxonomyTest {

    @Test
    fun `Domains ALL contains all known domains`() {
        assertEquals(7, IntentTaxonomy.Domains.ALL.size)
        assertTrue(IntentTaxonomy.Domains.ALL.contains(IntentTaxonomy.Domains.AUDIO))
        assertTrue(IntentTaxonomy.Domains.ALL.contains(IntentTaxonomy.Domains.SETTINGS))
        assertTrue(IntentTaxonomy.Domains.ALL.contains(IntentTaxonomy.Domains.MAPS))
        assertTrue(IntentTaxonomy.Domains.ALL.contains(IntentTaxonomy.Domains.MESSAGING))
        assertTrue(IntentTaxonomy.Domains.ALL.contains(IntentTaxonomy.Domains.SYSTEM))
        assertTrue(IntentTaxonomy.Domains.ALL.contains(IntentTaxonomy.Domains.HOME))
        assertTrue(IntentTaxonomy.Domains.ALL.contains(IntentTaxonomy.Domains.SEARCH))
    }

    @Test
    fun `Actions ALL contains all known actions`() {
        assertEquals(14, IntentTaxonomy.Actions.ALL.size)
        assertTrue(IntentTaxonomy.Actions.ALL.contains("launch"))
    }

    @Test
    fun `getActionsForDomain returns correct actions for audio`() {
        val actions = IntentTaxonomy.getActionsForDomain(IntentTaxonomy.Domains.AUDIO)
        assertEquals(5, actions.size)
        assertTrue(actions.contains(IntentTaxonomy.Actions.PLAY))
        assertTrue(actions.contains(IntentTaxonomy.Actions.PAUSE))
        assertTrue(actions.contains(IntentTaxonomy.Actions.STOP))
        assertTrue(actions.contains(IntentTaxonomy.Actions.NEXT))
        assertTrue(actions.contains(IntentTaxonomy.Actions.PREV))
    }

    @Test
    fun `getActionsForDomain returns correct actions for settings`() {
        val actions = IntentTaxonomy.getActionsForDomain(IntentTaxonomy.Domains.SETTINGS)
        assertEquals(5, actions.size)
        assertTrue(actions.contains(IntentTaxonomy.Actions.VOLUME_UP))
        assertTrue(actions.contains(IntentTaxonomy.Actions.VOLUME_DOWN))
        assertTrue(actions.contains(IntentTaxonomy.Actions.WIFI_TOGGLE))
        assertTrue(actions.contains(IntentTaxonomy.Actions.BLUETOOTH_TOGGLE))
        assertTrue(actions.contains(IntentTaxonomy.Actions.GPS_TOGGLE))
    }

    @Test
    fun `getActionsForDomain returns navigate for maps`() {
        val actions = IntentTaxonomy.getActionsForDomain(IntentTaxonomy.Domains.MAPS)
        assertEquals(1, actions.size)
        assertEquals(IntentTaxonomy.Actions.NAVIGATE, actions.first())
    }

    @Test
    fun `getActionsForDomain returns send for messaging`() {
        val actions = IntentTaxonomy.getActionsForDomain(IntentTaxonomy.Domains.MESSAGING)
        assertEquals(1, actions.size)
        assertEquals(IntentTaxonomy.Actions.SEND, actions.first())
    }

    @Test
    fun `getActionsForDomain returns query for search`() {
        val actions = IntentTaxonomy.getActionsForDomain(IntentTaxonomy.Domains.SEARCH)
        assertEquals(1, actions.size)
        assertEquals(IntentTaxonomy.Actions.QUERY, actions.first())
    }

    @Test
    fun `getActionsForDomain returns launch for unknown domain`() {
        val actions = IntentTaxonomy.getActionsForDomain("custom_domain")
        assertEquals(1, actions.size)
        assertEquals("launch", actions.first())
    }

    @Test
    fun `LegacyMapper maps audio_spotify correctly`() {
        val mapped = IntentTaxonomy.LegacyMapper.fromActionType("audio_spotify")
        assertNotNull(mapped)
        assertEquals(IntentTaxonomy.Domains.AUDIO, mapped!!.domain)
        assertEquals(IntentTaxonomy.Actions.PLAY, mapped.action)
        assertEquals("com.spotify.music", mapped.targetApp)
    }

    @Test
    fun `LegacyMapper maps waze_nav correctly`() {
        val mapped = IntentTaxonomy.LegacyMapper.fromActionType("waze_nav")
        assertNotNull(mapped)
        assertEquals(IntentTaxonomy.Domains.MAPS, mapped!!.domain)
        assertEquals(IntentTaxonomy.Actions.NAVIGATE, mapped.action)
        assertEquals("com.waze", mapped.targetApp)
    }

    @Test
    fun `LegacyMapper returns null for unknown actionType`() {
        val mapped = IntentTaxonomy.LegacyMapper.fromActionType("unknown_action")
        assertNull(mapped)
    }

    @Test
    fun `LegacyMapper maps vol_up correctly`() {
        val mapped = IntentTaxonomy.LegacyMapper.fromActionType("vol_up")
        assertNotNull(mapped)
        assertEquals(IntentTaxonomy.Domains.SETTINGS, mapped!!.domain)
        assertEquals(IntentTaxonomy.Actions.VOLUME_UP, mapped.action)
        assertNull(mapped.targetApp)
    }
}
