package com.voxapps.expenses.data

import android.util.Log
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The shipped `external_services.json` against the code that reads it.
 *
 * This file describes a service the app does not own — its endpoint and the page where a user gets
 * a key are the provider's to change — so it is served from the repository like every other schema,
 * and what the repository serves is only as good as what is asserted about it here.
 */
class ExternalServiceConfigTest {

    private val gson = Gson()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("../$relative"), File("vox-expenses/$relative"))
            .firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")

    private fun shipped(): ExternalServicesSchema =
        gson.fromJson(
            repoFile("src/main/assets/schemas/external_services.json").readText(),
            ExternalServicesSchema::class.java
        )

    @Test
    fun `the shipped file matches the copy in remote-schemas`() {
        val asset = repoFile("src/main/assets/schemas/external_services.json")
        val twin = listOf(
            File("remote-schemas/expenses/external_services.json"),
            File("../remote-schemas/expenses/external_services.json")
        ).firstOrNull { it.exists() } ?: error("remote-schemas/expenses/external_services.json not found")

        assertEquals(asset.readText(), twin.readText())
    }

    @Test
    fun `the rate provider is declared and reachable over https`() {
        val service = shipped().services.firstOrNull { it.id == "exchangerate_api" }
            ?: error("no exchangerate_api entry")

        assertTrue(service.serviceUrl.startsWith("https://"))
        assertTrue("a paid-for service should say it needs a key", service.needsApiKey)
        assertTrue("a service needing a key should say where to get one", !service.helpUrl.isNullOrBlank())
    }

    /**
     * The key travels in the path for this provider, so the placeholder is what says a credential is
     * needed — without it the probe would go out with an empty key and report the service
     * unreachable when it is the key that is missing.
     */
    @Test
    fun `the probe needs a key and puts it where the service expects it`() {
        val service = shipped().services.first { it.id == "exchangerate_api" }

        val withoutKey = service.probeSpec(null) ?: error("no probe declared")
        val withKey = service.probeSpec("abc123") ?: error("no probe declared")

        assertTrue(withoutKey.missingCredential)
        assertFalse(withKey.missingCredential)
        assertEquals("https://v6.exchangerate-api.com/v6/{key}/latest/USD", withKey.url)
    }

    /** The older spellings still parse, since a repository copy may predate the shared vocabulary. */
    @Test
    fun `a copy written before the vocabulary still resolves`() {
        val legacy = gson.fromJson(
            """
            {
              "schema_version": 1,
              "services": [{
                "id": "exchangerate_api",
                "baseEndpoint": "https://v6.exchangerate-api.com/v6",
                "requiresApiKey": true,
                "docsUrl": "https://www.exchangerate-api.com/docs/free"
              }]
            }
            """.trimIndent(),
            ExternalServicesSchema::class.java
        )

        val service = legacy.services.first()
        assertEquals("https://v6.exchangerate-api.com/v6", service.serviceUrl)
        assertTrue(service.needsApiKey)
        assertEquals("https://www.exchangerate-api.com/docs/free", service.helpUrl)
    }

    /** A service with nothing to reach has nothing to test, and says so by returning null. */
    @Test
    fun `a service with no endpoint yields no probe`() {
        assertNull(ExternalService(id = "nothing").probeSpec("key"))
    }

    /**
     * The call itself is declared, not compiled in.
     *
     * This provider takes its key in the path; another takes it as a query parameter and answers
     * under a different field. Writing either into the code is what stopped anyone pointing this at
     * a provider of their own — the whole reason the file is served from a repository.
     */
    @Test
    fun `the rates call is built from the declaration`() {
        val service = shipped().services.first { it.id == "exchangerate_api" }

        assertEquals(
            "https://v6.exchangerate-api.com/v6/abc123/latest/RON",
            service.ratesUrl("abc123", "ron")
        )
        assertEquals("conversion_rates", service.ratesPath)
    }

    /** A provider whose key travels as a query parameter, declared the way one would be. */
    @Test
    fun `a query-parameter provider builds its own shape`() {
        val service = ExternalService(
            id = "openexchangerates",
            endpoint = "https://openexchangerates.org/api",
            ratesUrl = "/latest.json?app_id={key}&base={base}",
            ratesPath = "rates"
        )

        assertEquals(
            "https://openexchangerates.org/api/latest.json?app_id=k1&base=EUR",
            service.ratesUrl("k1", "eur")
        )
    }

    /** A service this app cannot ask says so by returning null, rather than a guessed URL. */
    @Test
    fun `a service declaring no rates url yields none`() {
        val service = ExternalService(id = "mystery", endpoint = "https://example.com")

        assertNull(service.ratesUrl("k", "USD"))
    }
}
