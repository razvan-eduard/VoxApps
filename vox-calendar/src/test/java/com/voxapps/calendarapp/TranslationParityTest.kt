package com.voxapps.calendarapp

import com.voxapps.i18n.TranslationParity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every language this app ships carries exactly the key set English does — see [TranslationParity]. */
class TranslationParityTest {

    @Test
    fun `all shipped languages carry the same keys`() {
        val dir = File("src/main/assets/translations")
        val keysByLanguage = dir.listFiles { f -> f.extension == "json" }!!.associate { file ->
            val obj = JSONObject(file.readText())
            file.nameWithoutExtension to obj.keys().asSequence().toSet()
        }
        assertEquals(emptyList<String>(), TranslationParity.problems(keysByLanguage))
    }
}
