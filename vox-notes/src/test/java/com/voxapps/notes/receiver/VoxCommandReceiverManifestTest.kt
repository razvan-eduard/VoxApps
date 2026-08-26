package com.voxapps.notes.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the "com.voxapps.vox.nluHint" meta-data declared on VoxCommandReceiver in
 * AndroidManifest.xml — this is how vox-notes teaches Commander's LLM prompt about its
 * domain-specific "category" field, without Commander/models.json ever needing an edit for it
 * (see PromptProvider.buildSatelliteHints in vox-commander).
 */
class VoxCommandReceiverManifestTest {

    private fun metaDataValue(name: String): String? {
        val manifest = File("src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val metaDataNodes = doc.getElementsByTagName("meta-data")
        for (i in 0 until metaDataNodes.length) {
            val node = metaDataNodes.item(i)
            val attrs = node.attributes
            if (attrs.getNamedItem("android:name")?.nodeValue == name) {
                return attrs.getNamedItem("android:value")?.nodeValue
            }
        }
        return null
    }

    @Test
    fun `notes domain declares an nluHint teaching the category field`() {
        val hint = metaDataValue("com.voxapps.vox.nluHint")
        assertTrue("VoxCommandReceiver must declare com.voxapps.vox.nluHint", !hint.isNullOrBlank())
        assertTrue("nluHint must describe the category field", hint!!.contains("category"))
    }

    @Test
    fun `domain and actions meta-data are unaffected by the nluHint addition`() {
        assertEquals("notes", metaDataValue("com.voxapps.vox.domain"))
        assertEquals("ping,create,read,export,import,get_schema,sync_export,sync_merge,get_field_schema", metaDataValue("com.voxapps.vox.actions"))
    }
}
