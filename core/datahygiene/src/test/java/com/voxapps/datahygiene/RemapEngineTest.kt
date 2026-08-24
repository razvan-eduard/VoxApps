package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Test

class RemapEngineTest {

    private data class Rec(
        val vendor: String? = null,
        val location: String? = null,
        val category: String? = null,
        val title: String? = null,
        val amount: String? = null
    )

    private val engine = RemapEngine<Rec>(
        matchFields = listOf(
            RemapMatchField("vendor", "vendor") { it.vendor },
            RemapMatchField("location", "location") { it.location },
            RemapMatchField("amount", "amount", numeric = true) { it.amount }
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
        sortOrder: Int = 0,
        fuzz: Map<String, Int> = emptyMap()
    ) = RemapRule(
        id,
        RemapTrigger.all(match.map { (f, v) -> RemapCondition(f, v, fuzz[f] ?: 0) }),
        set,
        sortOrder
    )

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
        val rules = listOf(rule(1, mapOf("vendor" to "shell"), mapOf("category" to "Fuel"), fuzz = mapOf("vendor" to 2)))
        assertEquals("Fuel", fuzzyEngine.apply(Rec(vendor = "Shellz Station"), rules).category)
        assertEquals(listOf(Triple("Shellz Station", "shell", 2)), calls)

        // The same rule with no fuzz entry is exact again and the matcher is never consulted.
        calls.clear()
        val exactRules = listOf(rule(1, mapOf("vendor" to "shell"), mapOf("category" to "Fuel")))
        assertEquals(null, fuzzyEngine.apply(Rec(vendor = "Shellz Station"), exactRules).category)
        assertEquals(emptyList<Triple<String, String, Int>>(), calls)
    }

    @Test
    fun `fuzz without an injected matcher degrades to exact`() {
        val rules = listOf(
            rule(1, mapOf("vendor" to "shell"), mapOf("category" to "Fuel"), fuzz = mapOf("vendor" to 3))
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

    // --- alternatives: OR between groups, AND inside one ---

    /** The rule this shape exists for: one field, two acceptable answers. */
    @Test
    fun `one field asked about twice fires on either answer`() {
        val rules = listOf(
            RemapRule(
                1,
                RemapTrigger.anyOf("vendor", listOf("lidl", "carrefour")),
                mapOf("category" to "Shopping")
            )
        )
        assertEquals("Shopping", engine.apply(Rec(vendor = "LIDL"), rules).category)
        assertEquals("Shopping", engine.apply(Rec(vendor = "Carrefour"), rules).category)
        assertEquals(null, engine.apply(Rec(vendor = "Kaufland"), rules).category)
    }

    @Test
    fun `a group still requires all of its conditions`() {
        val rules = listOf(
            RemapRule(
                1,
                RemapTrigger.all(listOf(RemapCondition("vendor", "lidl"), RemapCondition("location", "cluj"))),
                mapOf("category" to "Shopping")
            )
        )
        assertEquals("Shopping", engine.apply(Rec(vendor = "Lidl", location = "Cluj"), rules).category)
        assertEquals(null, engine.apply(Rec(vendor = "Lidl", location = "Bucuresti"), rules).category)
    }

    /** What a single AND/OR switch on the rule could not express at all. */
    @Test
    fun `an and-group and a lone condition can be alternatives of one rule`() {
        val rules = listOf(
            RemapRule(
                1,
                RemapTrigger(
                    listOf(
                        listOf(RemapCondition("vendor", "lidl"), RemapCondition("location", "cluj")),
                        listOf(RemapCondition("vendor", "carrefour"))
                    )
                ),
                mapOf("category" to "Shopping")
            )
        )
        assertEquals("Shopping", engine.apply(Rec(vendor = "Lidl", location = "Cluj"), rules).category)
        assertEquals("and the lone alternative needs nothing else", "Shopping",
            engine.apply(Rec(vendor = "Carrefour"), rules).category)
        assertEquals("but the paired one still needs its pair", null,
            engine.apply(Rec(vendor = "Lidl", location = "Bucuresti"), rules).category)
    }

    @Test
    fun `an empty alternative never fires on its own`() {
        val rules = listOf(RemapRule(1, RemapTrigger(listOf(emptyList())), mapOf("category" to "X")))
        assertEquals(null, engine.apply(Rec(vendor = "anything"), rules).category)
    }

    // --- precedence ---

    /**
     * A rule fires on whichever alternative is easiest to satisfy, so that is what says how
     * demanding it is. Counting every condition would rank a broad rule with many alternatives as
     * the most precise — the opposite of what it is.
     */
    @Test
    fun `a rule is ranked by its weakest alternative, not its condition count`() {
        val broad = RemapRule(
            1,
            RemapTrigger.anyOf("vendor", listOf("lidl", "carrefour", "kaufland")),
            mapOf("category" to "Broad")
        )
        val narrow = RemapRule(
            2,
            RemapTrigger.all(listOf(RemapCondition("vendor", "lidl"), RemapCondition("location", "cluj"))),
            mapOf("category" to "Narrow")
        )
        val out = engine.apply(Rec(vendor = "Lidl", location = "Cluj"), listOf(broad, narrow))
        assertEquals("the two-condition alternative outranks three loose ones", "Narrow", out.category)
    }

    // --- quantities, which answer more than "is it this figure" ---

    @Test
    fun `over and under are the questions an amount answers`() {
        val over = listOf(
            RemapRule(1, RemapTrigger.all(listOf(RemapCondition("amount", "10000", op = RemapOp.GT))), mapOf("category" to "Big"))
        )
        assertEquals("Big", engine.apply(Rec(amount = "10001"), over).category)
        assertEquals(null, engine.apply(Rec(amount = "10000"), over).category)

        val atLeast = listOf(
            RemapRule(1, RemapTrigger.all(listOf(RemapCondition("amount", "10000", op = RemapOp.GTE))), mapOf("category" to "Big"))
        )
        assertEquals("Big", engine.apply(Rec(amount = "10000"), atLeast).category)
        assertEquals(null, engine.apply(Rec(amount = "9999"), atLeast).category)
    }

    /** A record with nothing to compare is not below every threshold — it is a record the question
     *  was never about. */
    @Test
    fun `an unreadable or absent quantity matches no comparison`() {
        val under = listOf(
            RemapRule(1, RemapTrigger.all(listOf(RemapCondition("amount", "10000", op = RemapOp.LT))), mapOf("category" to "Small"))
        )
        assertEquals(null, engine.apply(Rec(amount = null), under).category)
        assertEquals(null, engine.apply(Rec(amount = "many"), under).category)
    }

    /** Ordering a name would be a question with no answer, so the field has to admit it first. */
    @Test
    fun `a comparison on a field that is not a quantity never holds`() {
        val rules = listOf(
            RemapRule(1, RemapTrigger.all(listOf(RemapCondition("vendor", "lidl", op = RemapOp.GT))), mapOf("category" to "X"))
        )
        assertEquals(null, engine.apply(Rec(vendor = "shell"), rules).category)
        assertEquals(null, engine.apply(Rec(vendor = "lidl"), rules).category)
    }

    // --- a consequence that is not a field ---

    @Test
    fun `a rule that only alerts is evaluated and changes nothing`() {
        val rules = listOf(
            RemapRule(1, RemapTrigger.all(listOf(RemapCondition("vendor", "lidl"))), emptyMap(), alert = true)
        )
        val outcome = engine.evaluate(Rec(vendor = "Lidl", category = "Kept"), rules)
        assertEquals("Kept", outcome.record.category)
        assertEquals(listOf(1L), outcome.fired.map { it.id })
    }

    @Test
    fun `a rule may rewrite and alert at once`() {
        val rules = listOf(
            RemapRule(1, RemapTrigger.all(listOf(RemapCondition("vendor", "lidl"))), mapOf("category" to "Groceries"), alert = true)
        )
        val outcome = engine.evaluate(Rec(vendor = "Lidl"), rules)
        assertEquals("Groceries", outcome.record.category)
        assertEquals(listOf(1L), outcome.fired.map { it.id })
    }

    @Test
    fun `a rule with no consequence at all is skipped`() {
        val rules = listOf(RemapRule(1, RemapTrigger.all(listOf(RemapCondition("vendor", "lidl"))), emptyMap()))
        assertEquals(emptyList<Long>(), engine.evaluate(Rec(vendor = "Lidl"), rules).fired.map { it.id })
    }

    @Test
    fun `only the rules that recognised the record are reported`() {
        val rules = listOf(
            RemapRule(1, RemapTrigger.all(listOf(RemapCondition("vendor", "lidl"))), emptyMap(), alert = true),
            RemapRule(2, RemapTrigger.all(listOf(RemapCondition("vendor", "shell"))), emptyMap(), alert = true)
        )
        assertEquals(listOf(2L), engine.evaluate(Rec(vendor = "Shell"), rules).fired.map { it.id })
    }
}
