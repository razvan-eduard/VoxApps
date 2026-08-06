package com.voxapps.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `:core:backup` doesn't depend on vox-hub, so this can't call Hub's real `ExportImportUtil`
 * directly — instead these tests replicate its exact documented shape
 * (`{"exported_at":..., "schema_version":1, "apps":{"<domain>":{...}}}`) as a golden fixture, so a
 * drift between the two independent implementations shows up here rather than only at runtime.
 */
class VoxBackupDocumentTest {

    @Test
    fun `build wraps a domain's export json in Hub's apps-wrapper shape`() {
        val text = VoxBackupDocument.build("notes", """{"settings":{"language":"en"}}""")
        val root = JSONObject(text)

        assertEquals(VoxBackupDocument.SCHEMA_VERSION, root.getInt("schema_version"))
        assertEquals(true, root.has("exported_at"))
        val notesObj = root.getJSONObject("apps").getJSONObject("notes")
        assertEquals("en", notesObj.getJSONObject("settings").getString("language"))
    }

    @Test
    fun `parseForDomain injects the outer exported_at into the domain's sub-object`() {
        // Hand-built exactly as Hub's ExportImportUtil.buildExportDocument would produce it —
        // the golden fixture this test guards.
        val hubShapedDocument = """
            {"exported_at":1700000000000,"schema_version":1,
             "apps":{"expenses":{"categories":[]},"notes":{"notes":[]}}}
        """.trimIndent()

        val expensesObj = VoxBackupDocument.parseForDomain(hubShapedDocument, "expenses")

        assertEquals(1700000000000L, expensesObj?.optLong("exported_at"))
        assertEquals(0, expensesObj?.getJSONArray("categories")?.length())
    }

    @Test
    fun `parseForDomain returns null when the domain is absent`() {
        val hubShapedDocument = """{"exported_at":1,"schema_version":1,"apps":{"notes":{}}}"""

        assertNull(VoxBackupDocument.parseForDomain(hubShapedDocument, "expenses"))
    }

    @Test
    fun `parseForDomain returns null for a document with no apps wrapper at all`() {
        assertNull(VoxBackupDocument.parseForDomain("""{"foo":"bar"}""", "notes"))
    }

    @Test
    fun `round-trip through build then parseForDomain preserves the original export json`() {
        val originalJson = """{"settings":{"language":"ro"},"categories":[{"id":1,"name":"Food"}]}"""

        val document = VoxBackupDocument.build("expenses", originalJson)
        val parsed = VoxBackupDocument.parseForDomain(document, "expenses")

        assertEquals("ro", parsed?.getJSONObject("settings")?.getString("language"))
        assertEquals("Food", parsed?.getJSONArray("categories")?.getJSONObject(0)?.getString("name"))
    }
}
