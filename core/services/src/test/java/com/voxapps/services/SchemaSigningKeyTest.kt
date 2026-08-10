package com.voxapps.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The key the apps verify with must be the key this repository signs with.
 *
 * It exists in two places — `remote-schemas/signing-key.pub`, which `vox schemas sign|verify` uses,
 * and a constant inside [SchemaSignature], which the apps compile in. Nothing at build time links
 * them, and the failure when they drift is silent in the worst way:
 *
 *  - **Rotating the key here** and forgetting the constant leaves every install verifying against a
 *    key nothing signs with any more. Schema updates simply stop arriving, with no error anywhere.
 *  - **Forking this repository** and running `vox schemas keygen` writes a new public key, signs with
 *    it, and changes nothing about what the fork's own app trusts — so the fork's signing is
 *    decorative and its schemas quietly count as unverified.
 *
 * Embedding the constant rather than reading the file at runtime is deliberate: the trust anchor
 * should be inside the signed APK, not loaded from anywhere. This test is what makes the duplication
 * safe — it turns a silent mismatch into a failed build that says which line to change.
 */
class SchemaSigningKeyTest {

    /** Unit tests run with the module directory as the working directory. */
    private val publicKeyFile = File("../../remote-schemas/signing-key.pub")

    @Test
    fun `the embedded key is the one this repository signs with`() {
        assertTrue(
            "remote-schemas/signing-key.pub is missing — schema signing cannot be verified",
            publicKeyFile.exists()
        )

        val fromFile = publicKeyFile.readLines()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()

        val embedded = SchemaSignature.embeddedPublicKeyForTest

        assertEquals(
            "The key in remote-schemas/signing-key.pub does not match the one compiled into " +
                "SchemaSignature. If you rotated the key or forked this repository, update " +
                "PUBLIC_KEY_B64 in SchemaSignature.kt to the value below — until you do, this " +
                "build verifies against a key nothing signs with.",
            fromFile,
            embedded
        )
    }
}
