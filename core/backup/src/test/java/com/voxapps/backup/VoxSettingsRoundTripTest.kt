package com.voxapps.backup

import org.junit.Assert.assertEquals
import org.junit.Test

private data class FakeSettings(
    val name: String = "default",
    val ttl: String = "ONE_DAY",
    val tags: List<String> = emptyList()
)

class VoxSettingsRoundTripTest {

    @Test
    fun `round-trips a normal settings object`() {
        val settings = FakeSettings(name = "custom", ttl = "FOREVER", tags = listOf("a", "b"))
        val json = VoxSettingsRoundTrip.toJson(settings)
        val parsed = VoxSettingsRoundTrip.parseOrDefault(json, FakeSettings::class.java, FakeSettings())
        assertEquals(settings, parsed)
    }

    @Test
    fun `returns default on malformed json`() {
        val default = FakeSettings(name = "fallback")
        val parsed = VoxSettingsRoundTrip.parseOrDefault("not valid json {{{", FakeSettings::class.java, default)
        assertEquals(default, parsed)
    }

    @Test
    fun `coalesce lambda backfills a field an older payload leaves genuinely null`() {
        // A JSON key that's explicitly present but null (not merely omitted) reliably bypasses
        // Kotlin's non-null guarantee via Gson's reflective construction, regardless of whether the
        // class also has a default value for that parameter — the exact real-world shape of an old
        // export whose field was, at the time, nullable/absent-by-convention. `coalesce` is what
        // closes that gap explicitly, at the call site, rather than relying on Gson to always infer
        // the right default (which the historical searchProviderApiKeys/paymentSourcePackages bugs
        // in this codebase's larger settings classes prove it doesn't reliably do).
        val jsonWithExplicitNull = """{"name":"custom","tags":["a"],"ttl":null}"""
        val parsed = VoxSettingsRoundTrip.parseOrDefault(
            jsonWithExplicitNull,
            FakeSettings::class.java,
            FakeSettings(),
            coalesce = { it.copy(ttl = it.ttl ?: "ONE_DAY") }
        )
        assertEquals("ONE_DAY", parsed.ttl)
    }
}
