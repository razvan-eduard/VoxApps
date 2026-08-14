package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Test

class RemapEngineTest {

    private data class Rec(
        val vendor: String? = null,
        val location: String? = null,
        val category: String? = null,
        val title: String? = null
    )

    private val engine = RemapEngine<Rec>(
        matchFields = listOf(
            RemapMatchField("vendor", "vendor") { it.vendor },
            RemapMatchField("location", "location") { it.location }
        ),
        setFields = listOf(
            RemapSetField("vendor", "vendor") { r, v -> r.copy(vendor = v) },
            RemapSetField("category", "category") { r, v -> if (v == "INVALID") null else r.copy(category = v) },
            RemapSetField("title", "title") { r, v -> r.copy(title = v) }
        )
    )

    private fun rule(
        id: Long,
        match: Map<String, String>,
        set: Map<String, String>,
        sortOrder: Int = 0
    ) = RemapRule(id, match, set, sortOrder)

    @Test
    fun `matching is exact on normalized values`() {
        val rules = listOf(rule(1, mapOf("vendor" to "lazar ionut pfa"), mapOf("category" to "Groceries")))
        assertEquals("Groceries", engine.apply(Rec(vendor = "  LAZAR IONUT PFA "), rules).category)
        // A near-miss never fires.
        assertEquals(null, engine.apply(Rec(vendor = "LAZAR IONUT SRL"), rules).category)
    }

    @Test
    fun `all match fields must agree`() {
        val rules = listOf(
            rule(1, mapOf("vendor" to "shell", "location" to "cluj"), mapOf("category" to "Fuel"))
        )
        assertEquals(null, engine.apply(Rec(vendor = "Shell"), rules).category)
        assertEquals("Fuel", engine.apply(Rec(vendor = "Shell", location = "Cluj"), rules).category)
    }

    @Test
    fun `more specific match wins within an origin`() {
        val rules = listOf(
            rule(1, mapOf("vendor" to "shell"), mapOf("category" to "Broad")),
            rule(2, mapOf("vendor" to "shell", "location" to "cluj"), mapOf("category" to "Specific"))
        )
        assertEquals("Specific", engine.apply(Rec(vendor = "shell", location = "cluj"), rules).category)
        assertEquals("Broad", engine.apply(Rec(vendor = "shell", location = "iasi"), rules).category)
    }

    @Test
    fun `sortOrder then id break remaining ties`() {
        val bySort = listOf(
            rule(9, mapOf("vendor" to "shell"), mapOf("category" to "First"), sortOrder = 0),
            rule(1, mapOf("vendor" to "shell"), mapOf("category" to "Second"), sortOrder = 1)
        )
        assertEquals("First", engine.apply(Rec(vendor = "shell"), bySort).category)
        val byId = listOf(
            rule(2, mapOf("vendor" to "shell"), mapOf("category" to "Later")),
            rule(1, mapOf("vendor" to "shell"), mapOf("category" to "Earlier"))
        )
        assertEquals("Earlier", engine.apply(Rec(vendor = "shell"), byId).category)
    }

    @Test
    fun `losing rules still fill unclaimed fields`() {
        val rules = listOf(
            rule(1, mapOf("vendor" to "shell", "location" to "cluj"), mapOf("category" to "Fuel")),
            rule(2, mapOf("vendor" to "shell"), mapOf("category" to "Broad", "vendor" to "Shell Romania"))
        )
        val out = engine.apply(Rec(vendor = "shell", location = "cluj"), rules)
        assertEquals("Fuel", out.category)
        assertEquals("Shell Romania", out.vendor)
    }

    @Test
    fun `rules never chain`() {
        val rules = listOf(
            rule(1, mapOf("vendor" to "shell"), mapOf("vendor" to "OMV")),
            rule(2, mapOf("vendor" to "omv"), mapOf("category" to "Chained"))
        )
        val out = engine.apply(Rec(vendor = "shell"), rules)
        assertEquals("OMV", out.vendor)
        assertEquals(null, out.category)
    }

    @Test
    fun `a declining setter leaves the field claimable by the next rule`() {
        val rules = listOf(
            rule(1, mapOf("vendor" to "shell"), mapOf("category" to "INVALID")),
            rule(2, mapOf("vendor" to "shell"), mapOf("category" to "Fallback"))
        )
        assertEquals("Fallback", engine.apply(Rec(vendor = "shell"), rules).category)
    }

    @Test
    fun `a fuzz level routes through the injected matcher, exact entries never do`() {
        val calls = mutableListOf<Triple<String, String, Int>>()
        val fuzzyEngine = RemapEngine<Rec>(
            matchFields = listOf(RemapMatchField("vendor", "vendor") { it.vendor }),
            setFields = listOf(RemapSetField("category", "category") { r, v -> r.copy(category = v) }),
            leveledMatcher = { a, b, level -> calls += Triple(a, b, level); a.lowercase().startsWith(b.take(3)) }
        )
        val rules = listOf(
            RemapRule(1, mapOf("vendor" to "shell"), mapOf("category" to "Fuel"), fuzz = mapOf("vendor" to 2))
        )
        assertEquals("Fuel", fuzzyEngine.apply(Rec(vendor = "Shellz Station"), rules).category)
        assertEquals(listOf(Triple("Shellz Station", "shell", 2)), calls)

        // The same rule with no fuzz entry is exact again and the matcher is never consulted.
        calls.clear()
        val exactRules = listOf(rules[0].copy(fuzz = emptyMap()))
        assertEquals(null, fuzzyEngine.apply(Rec(vendor = "Shellz Station"), exactRules).category)
        assertEquals(emptyList<Triple<String, String, Int>>(), calls)
    }

    @Test
    fun `fuzz without an injected matcher degrades to exact`() {
        val rules = listOf(
            rule(1, mapOf("vendor" to "shell"), mapOf("category" to "Fuel"))
                .copy(fuzz = mapOf("vendor" to 3))
        )
        assertEquals(null, engine.apply(Rec(vendor = "Shellz Station"), rules).category)
        assertEquals("Fuel", engine.apply(Rec(vendor = "SHELL"), rules).category)
    }

    @Test
    fun `empty match or set never fires`() {
        val rules = listOf(
            rule(1, emptyMap(), mapOf("category" to "Everything")),
            rule(2, mapOf("vendor" to "shell"), emptyMap())
        )
        assertEquals(Rec(vendor = "shell"), engine.apply(Rec(vendor = "shell"), rules))
    }
}
