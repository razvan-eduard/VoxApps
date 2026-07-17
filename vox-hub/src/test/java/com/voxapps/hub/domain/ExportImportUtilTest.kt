package com.voxapps.hub.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportImportUtilTest {

    @Test
    fun `buildExportDocument wraps every domain under the documented shape`() {
        val document = ExportImportUtil.buildExportDocument(mapOf("expenses" to """{"totalAmount":1}""", "notes" to """{"notes":[]}"""))
        val root = JSONObject(document)

        assertTrue(root.has("exported_at"))
        assertEquals(ExportImportUtil.SCHEMA_VERSION, root.getInt("schema_version"))
        assertEquals(1, root.getJSONObject("apps").getJSONObject("expenses").getInt("totalAmount"))
    }

    @Test
    fun `parseImportDocument injects exported_at into every per-domain object`() {
        val document = """{"exported_at":123456,"schema_version":1,"apps":{"expenses":{"a":1},"notes":{"b":2}}}"""

        val perDomain = ExportImportUtil.parseImportDocument(document)

        assertEquals(123456L, perDomain.getValue("expenses").getLong("exported_at"))
        assertEquals(123456L, perDomain.getValue("notes").getLong("exported_at"))
    }

    @Test
    fun `parseImportDocument defaults every domain to 0L when exported_at is missing`() {
        val document = """{"schema_version":1,"apps":{"expenses":{"a":1}}}"""

        val perDomain = ExportImportUtil.parseImportDocument(document)

        assertEquals(0L, perDomain.getValue("expenses").getLong("exported_at"))
    }

    @Test
    fun `parseImportDocument on a document with no apps returns an empty map`() {
        assertEquals(emptyMap<String, JSONObject>(), ExportImportUtil.parseImportDocument("""{"exported_at":1}"""))
    }

    @Test
    fun `summarize only counts known array keys, unaffected by the injected scalar`() {
        val data = JSONObject("""{"exported_at":123,"expenses":[1,2,3],"categories":[1],"unrelatedKey":"x"}""")

        val counts = ExportImportUtil.summarize(data)

        assertEquals(mapOf("expenses" to 3, "categories" to 1), counts)
    }
}
