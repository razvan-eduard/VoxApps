package com.voxapps.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a fetched schema may change what the app does.
 *
 * These schemas name engine endpoints and carry the NLU prompt, and they are adopted unattended at
 * every launch — so "which repository is this, and is it signed" is the whole of the trust decision.
 */
class SchemaSignatureTest {

    @Test
    fun `the default repository is recognised`() {
        assertTrue(SchemaSignature.isDefaultRepo(SchemaRepo.DEFAULT_BASE_URL))
    }

    @Test
    fun `a trailing slash does not make it someone else's repository`() {
        assertTrue(SchemaSignature.isDefaultRepo(SchemaRepo.DEFAULT_BASE_URL + "/"))
    }

    @Test
    fun `a branch suffix does not make it someone else's repository`() {
        assertTrue(SchemaSignature.isDefaultRepo(SchemaRepo.DEFAULT_BASE_URL + "@main"))
    }

    /**
     * The project was VoxCommander before VoxApps, and the URL is persisted per install. An install
     * that saved the old name follows the same repository — demoting it to "a fork" would quietly
     * downgrade every existing user from signed to unverified on upgrade.
     */
    @Test
    fun `the pre-rename name is still the same repository`() {
        assertTrue(SchemaSignature.isDefaultRepo("https://github.com/razvan-eduard/VoxCommander"))
        assertTrue(SchemaSignature.isDefaultRepo("https://github.com/razvan-eduard/VoxCommander@main"))
    }

    @Test
    fun `somebody else's repository is not the default`() {
        assertFalse(SchemaSignature.isDefaultRepo("https://github.com/someone/VoxApps"))
        assertFalse(SchemaSignature.isDefaultRepo("https://example.com/schemas"))
    }

    /**
     * A near-miss must not pass. This is the case a substring check would get wrong, and it is the
     * one an attacker would pick.
     */
    @Test
    fun `a repository whose name merely contains the default is not the default`() {
        assertFalse(SchemaSignature.isDefaultRepo("https://github.com/razvan-eduard/VoxApps-evil"))
        assertFalse(SchemaSignature.isDefaultRepo("https://evil.com/https://github.com/razvan-eduard/VoxApps"))
    }

    /**
     * With no verified manifest — the state after a failed or absent signature — the default
     * repository must be refused and a fork merely marked. Fail closed where the key can speak;
     * stay usable where it cannot.
     */
    @Test
    fun `without a verified manifest the default repository is refused`() {
        val verdict = SchemaSignature.verdictFor("commander/models.json", "{}", isDefaultRepo = true)
        assertTrue(verdict == SchemaSignature.Verdict.FAILED)
    }

    /**
     * A valid signature does not make a manifest current. Someone who can serve these files but not
     * sign them can replay an old, genuinely signed manifest — every signature checking out while
     * the app walks backwards to a schema naming an endpoint since abandoned. The serial is what
     * makes that detectable, so it has to survive a round trip through the manifest format.
     */
    @Test
    fun `a serial is read back out of a manifest`() {
        val manifest = """{"version":1,"serial":1786345375,"files":{"a/b.json":"${"0".repeat(64)}"}}"""
        assertEquals(1786345375L, SchemaSignature.parseSerialForTest(manifest))
    }

    @Test
    fun `a manifest with no serial reads as zero rather than throwing`() {
        assertEquals(0L, SchemaSignature.parseSerialForTest("""{"version":1,"files":{}}"""))
    }

    @Test
    fun `without a verified manifest a fork is accepted but marked unverified`() {
        val verdict = SchemaSignature.verdictFor("commander/models.json", "{}", isDefaultRepo = false)
        assertTrue(verdict == SchemaSignature.Verdict.UNVERIFIED_FORK)
    }
}
