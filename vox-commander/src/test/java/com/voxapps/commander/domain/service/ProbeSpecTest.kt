package com.voxapps.commander.domain.service

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The rules a declaration must obey to become a request.
 *
 * These matter more than they look: the probe carries a service's credential, and the schemas
 * describing them can be served from a repository the user configured.
 */
class ProbeSpecTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @Test
    fun `a probe path is resolved against the endpoint`() {
        val spec = ProbeSpec.from("OPENAI", "https://api.openai.com/v1", "models")

        assertEquals("https://api.openai.com/v1/models", spec?.url)
    }

    /** A declaration should not repeat itself, and a service whose own URL answers need not. */
    @Test
    fun `no probe path means the endpoint itself`() {
        val spec = ProbeSpec.from("WeatherAPI", "https://api.weatherapi.com/v1/forecast.json")

        assertEquals("https://api.weatherapi.com/v1/forecast.json", spec?.url)
    }

    @Test
    fun `slashes on either side do not double up`() {
        val spec = ProbeSpec.from("piped", "https://pipedapi.kavin.rocks/", "/health")

        assertEquals("https://pipedapi.kavin.rocks/health", spec?.url)
    }

    /**
     * A search API's endpoint is complete but answers nothing without arguments: probing it bare
     * gets a 400, which would read as "unreachable" for a service that is up and working.
     */
    @Test
    fun `a query-only probe keeps the endpoint and adds arguments`() {
        val spec = ProbeSpec.from("WeatherAPI", "https://api.weatherapi.com/v1/forecast.json", "?q=London")

        assertEquals("https://api.weatherapi.com/v1/forecast.json?q=London", spec?.url)
    }

    /**
     * An endpoint that is already a full path needs a sibling of the whole path, not of its last
     * segment: OpenAI's search provider calls `/v1/chat/completions`, and the cheap check is
     * `/v1/models` — not `/v1/chat/completions/models`.
     */
    @Test
    fun `a leading slash resolves from the host root`() {
        val spec = ProbeSpec.from("OpenAI", "https://api.openai.com/v1/chat/completions", "/v1/models")

        assertEquals("https://api.openai.com/v1/models", spec?.url)
    }

    /** Still the same host, which is the property that matters. */
    @Test
    fun `a root-relative probe cannot leave the declared host`() {
        val spec = ProbeSpec.from("x", "https://api.example.com/deep/path", "/somewhere/else")

        assertEquals("https://api.example.com/somewhere/else", spec?.url)
    }

    /**
     * The security boundary. A path can only reach the host the endpoint already names; an absolute
     * URL could name any host at all, and the credential goes with the request.
     */
    @Test
    fun `an absolute probe URL is refused`() {
        val spec = ProbeSpec.from(
            id = "hostile",
            endpoint = "https://api.example.com/v1",
            probeUrl = "https://collector.example/take-it"
        )

        assertNull(spec)
    }

    @Test
    fun `an engine that declares no endpoint is not testable`() {
        assertNull(ProbeSpec.from("stt_whisper", endpoint = null))
        assertNull(ProbeSpec.from("wake_porcupine", endpoint = ""))
    }

    @Test
    fun `a service needing a credential it does not have is not worth asking`() {
        val withoutKey = ProbeSpec.from(
            "OPENAI", "https://api.openai.com/v1", "models",
            auth = ProbeSpec.AuthStyle.Bearer, credential = null
        )
        val withKey = withoutKey?.copy(credential = "sk-live")

        assertTrue(withoutKey!!.missingCredential)
        assertFalse(withKey!!.missingCredential)
    }

    /** A public endpoint is testable with no credential at all — reachability is its own answer. */
    @Test
    fun `a keyless service is still testable`() {
        val spec = ProbeSpec.from("piped", "https://pipedapi.kavin.rocks", "health")

        assertFalse(spec!!.missingCredential)
    }
}
