package com.voxapps.commander.domain.search

import android.util.Log
import com.voxapps.commander.data.preferences.Credentials
import com.voxapps.commander.data.remote.RemoteModelRegistry
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Where a provider's credential comes from.
 *
 * Two providers call OpenAI — the search provider and the intent engine — and asking for the same
 * key twice means keeping two copies in step. So a declaration can name the engine whose credential
 * to use, which is a schema naming something in the app: the interesting cases are the ones where
 * what it names is not there.
 */
class DynamicSearchProviderTest {

    private val store = Credentials(
        byEngine = mapOf("OPENAI" to "sk-engine"),
        bySearchProvider = mapOf("OpenAI" to "sk-own")
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.hasCapability(any(), any()) } returns false
        every { RemoteModelRegistry.hasCapability("OPENAI", "requires_api_key") } returns true
    }

    @After
    fun tearDown() = unmockkObject(RemoteModelRegistry)

    private fun provider(def: ProviderDefinition) =
        DynamicSearchProvider(def, "general") { store }

    private fun definition(
        sharedKeyEngine: String? = null,
        usesSharedApiKey: Boolean = false
    ) = ProviderDefinition(
        name = "OpenAI",
        endpoint = "https://api.openai.com/v1/chat/completions",
        requiresApiKey = true,
        sharedKeyEngine = sharedKeyEngine,
        usesSharedApiKey = usesSharedApiKey
    )

    @Test
    fun `a declared engine lends its credential`() {
        val provider = provider(definition(sharedKeyEngine = "OPENAI"))

        assertEquals("OPENAI", provider.borrowsFromEngine)
        assertEquals("sk-engine", provider.probeSpec()?.credential)
    }

    /**
     * The point of the guard. A schema served from a repository can name an engine this build does
     * not have, and the screen would then say where the key comes from while the field that enters
     * it renders nothing — that field draws nothing for an engine which declares no need for a key.
     * Falling back to the provider's own credential leaves it configurable.
     */
    @Test
    fun `an engine this build does not have is not borrowed from`() {
        val provider = provider(definition(sharedKeyEngine = "SOME_ENGINE_THAT_WENT_AWAY"))

        assertNull(provider.borrowsFromEngine)
        assertEquals("sk-own", provider.probeSpec()?.credential)
        assertTrue(provider.hasApiKey())
    }

    /** An engine that needs no credential has none to lend, whatever the declaration says. */
    @Test
    fun `an engine with no credential of its own is not borrowed from`() {
        val provider = provider(definition(sharedKeyEngine = "nlu_llm"))

        assertNull(provider.borrowsFromEngine)
    }

    /** The older spelling could only ever mean OpenAI, and still does. */
    @Test
    fun `the boolean spelling still borrows OpenAI's credential`() {
        val provider = provider(definition(usesSharedApiKey = true))

        assertEquals("OPENAI", provider.borrowsFromEngine)
        assertEquals("sk-engine", provider.probeSpec()?.credential)
    }

    /** Nothing declared: the provider owns its key, and reads it at the moment it is needed. */
    @Test
    fun `a provider with no declaration reads its own credential`() {
        val provider = provider(definition())

        assertNull(provider.borrowsFromEngine)
        assertEquals("sk-own", provider.probeSpec()?.credential)
    }

    @Test
    fun `a provider with no credential anywhere has none`() {
        val empty = DynamicSearchProvider(definition().copy(name = "GNews"), "news") { Credentials() }

        assertFalse(empty.hasApiKey())
        assertNull(empty.probeSpec()?.credential)
    }
}
