package com.voxapps.datahygiene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class RuleDummyRecord(val amount: Double, val title: String?, val vendor: String?)

private val amountField = RuleField<RuleDummyRecord>("amount", "amount") { c, e -> c.amount == e.amount }
private val titleField = RuleField<RuleDummyRecord>("title", "title") { c, e -> c.title == e.title }
private val vendorField = RuleField<RuleDummyRecord>("vendor", "vendor") { c, e -> c.vendor == e.vendor }
private val allFields = listOf(amountField, titleField, vendorField)

class RuleBasedDuplicateCheckerTest {

    @Test
    fun `a rule with AND requires every selected field to match`() {
        val rule = DuplicateRule(fieldIds = listOf("amount", "title"), combinator = RuleCombinator.AND)
        val checker = RuleBasedDuplicateChecker(allFields, listOf(rule), RuleCombinator.OR)

        val existing = RuleDummyRecord(amount = 10.0, title = "Coffee", vendor = "Cafe A")
        val bothMatch = RuleDummyRecord(amount = 10.0, title = "Coffee", vendor = "Cafe B")
        val onlyAmountMatches = RuleDummyRecord(amount = 10.0, title = "Tea", vendor = "Cafe A")

        assertTrue(checker.isDuplicateOf(bothMatch, existing))
        assertFalse(checker.isDuplicateOf(onlyAmountMatches, existing))
    }

    @Test
    fun `a rule with OR needs only one selected field to match`() {
        val rule = DuplicateRule(fieldIds = listOf("title", "vendor"), combinator = RuleCombinator.OR)
        val checker = RuleBasedDuplicateChecker(allFields, listOf(rule), RuleCombinator.OR)

        val existing = RuleDummyRecord(amount = 10.0, title = "Coffee", vendor = "Cafe A")
        val onlyVendorMatches = RuleDummyRecord(amount = 99.0, title = "Something else", vendor = "Cafe A")
        val neitherMatches = RuleDummyRecord(amount = 99.0, title = "Something else", vendor = "Cafe B")

        assertTrue(checker.isDuplicateOf(onlyVendorMatches, existing))
        assertFalse(checker.isDuplicateOf(neitherMatches, existing))
    }

    @Test
    fun `global OR needs only one rule to match, global AND needs every rule to match`() {
        val ruleA = DuplicateRule(fieldIds = listOf("amount"), combinator = RuleCombinator.AND)
        val ruleB = DuplicateRule(fieldIds = listOf("vendor"), combinator = RuleCombinator.AND)
        val orChecker = RuleBasedDuplicateChecker(allFields, listOf(ruleA, ruleB), RuleCombinator.OR)
        val andChecker = RuleBasedDuplicateChecker(allFields, listOf(ruleA, ruleB), RuleCombinator.AND)

        val existing = RuleDummyRecord(amount = 10.0, title = "Coffee", vendor = "Cafe A")
        val onlyAmountMatches = RuleDummyRecord(amount = 10.0, title = "Tea", vendor = "Cafe B")

        assertTrue(orChecker.isDuplicateOf(onlyAmountMatches, existing))
        assertFalse(andChecker.isDuplicateOf(onlyAmountMatches, existing))
    }

    @Test
    fun `a rule with no resolvable fields never matches, even under global OR`() {
        val emptyRule = DuplicateRule(fieldIds = emptyList(), combinator = RuleCombinator.AND)
        val realRule = DuplicateRule(fieldIds = listOf("unknown-field-id"), combinator = RuleCombinator.AND)
        val checker = RuleBasedDuplicateChecker(allFields, listOf(emptyRule, realRule), RuleCombinator.OR)

        val existing = RuleDummyRecord(amount = 10.0, title = "Coffee", vendor = "Cafe A")
        val identical = existing.copy()

        assertFalse(checker.isDuplicateOf(identical, existing))
    }

    @Test
    fun `no rules at all never matches`() {
        val checker = RuleBasedDuplicateChecker(allFields, emptyList(), RuleCombinator.OR)
        val existing = RuleDummyRecord(amount = 10.0, title = "Coffee", vendor = "Cafe A")

        assertFalse(checker.isDuplicateOf(existing.copy(), existing))
    }
}
