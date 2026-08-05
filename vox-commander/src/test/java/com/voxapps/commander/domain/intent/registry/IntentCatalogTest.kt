package com.voxapps.commander.domain.intent.registry

import com.google.gson.Gson
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden test for the data-driven intent catalog.
 *
 * The action strings in intents.json are the *literal* values of Android SDK
 * constants (MediaStore.*, Settings.*, AlarmClock.*, Intent.*). A single mistyped
 * character means the probe silently never matches and that intent vanishes at
 * runtime — something no on-device symptom points back to. Since those constants
 * are compile-time String literals (inlined by the Kotlin/Java compiler), this
 * JVM test can reference them directly and assert every one is present in the JSON.
 */
class IntentCatalogTest {

    private val gson = Gson()

    private fun readIntentsJson(): String {
        // Gradle runs unit tests with the module dir (app/) as CWD; the source of
        // truth lives at the repo root and is copied into assets by copyIntentsJson.
        val candidates = listOf(
            File("../intents.json"),
            File("intents.json"),
            File("src/main/assets/intents.json"),
            File("app/src/main/assets/intents.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("intents.json not found. Looked in: ${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun schema(): IntentCatalog.IntentsSchema =
        gson.fromJson(readIntentsJson(), IntentCatalog.IntentsSchema::class.java)

    @Test
    fun `intents json parses and is non-empty`() {
        val schema = schema()
        assertTrue("schema_version must be >= 1", schema.schemaVersion >= 1)
        assertTrue("intents must be non-empty", schema.intents.isNotEmpty())
    }

    @Test
    fun `taxonomy is present and covers the code vocabulary (superset invariant)`() {
        val tax = schema().taxonomy
        assertTrue("taxonomy block must be present", tax != null)
        tax!!

        // The JSON must not drop any domain/action the code relies on (handlers dispatch on these).
        // Extra (satellite) domains are allowed — this only checks the seed is a subset.
        val seedDomains = listOf("audio", "settings", "maps", "messaging", "system", "home", "search")
        for (d in seedDomains) {
            assertTrue("taxonomy.domains missing '$d'", tax.domains.contains(d))
        }

        val seedActions = listOf(
            "play", "pause", "stop", "next", "prev", "volume_up", "volume_down",
            "wifi_toggle", "bluetooth_toggle", "gps_toggle", "navigate", "send", "query", "launch"
        )
        for (a in seedActions) {
            assertTrue("taxonomy.actions missing '$a'", tax.actions.contains(a))
        }

        // Per-domain actions must match for the handler domains (can't silently drift).
        assertEquals(listOf("play", "pause", "stop", "next", "prev"), tax.actionsByDomain["audio"])
        assertEquals(
            listOf(
                "volume_up", "volume_down", "wifi_toggle", "bluetooth_toggle", "gps_toggle",
                "flashlight_on", "flashlight_off", "flashlight_toggle", "airplane_mode_toggle",
                "dnd_on", "dnd_off", "dnd_toggle", "nfc_toggle",
                "auto_rotate_on", "auto_rotate_off", "auto_rotate_toggle",
                "silent_mode_on", "silent_mode_off", "silent_mode_toggle"
            ),
            tax.actionsByDomain["settings"]
        )
        assertEquals(listOf("navigate"), tax.actionsByDomain["maps"])
        assertEquals(listOf("send"), tax.actionsByDomain["messaging"])
        assertEquals(listOf("query"), tax.actionsByDomain["search"])
    }

    @Test
    fun `every intent has a non-blank action and label`() {
        for (def in schema().intents) {
            assertTrue("blank action for '${def.label}'", def.action.isNotBlank())
            assertTrue("blank label for action '${def.action}'", def.label.isNotBlank())
        }
    }

    @Test
    fun `every SDK action constant is transcribed exactly into the catalog`() {
        val actions = schema().intents.map { it.action }.toSet()

        // These reads inline to the constants' literal string values at compile time.
        val required = listOf(
            android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH,
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
            android.provider.MediaStore.ACTION_VIDEO_CAPTURE,
            android.content.Intent.ACTION_SEARCH,
            android.content.Intent.ACTION_VIEW,
            android.content.Intent.ACTION_DIAL,
            android.content.Intent.ACTION_SENDTO,
            android.content.Intent.ACTION_SEND,
            android.content.Intent.ACTION_WEB_SEARCH,
            android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH,
            android.provider.Settings.ACTION_SETTINGS,
            android.provider.Settings.ACTION_WIFI_SETTINGS,
            android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
            android.provider.Settings.ACTION_SOUND_SETTINGS,
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.provider.Settings.ACTION_DISPLAY_SETTINGS,
            android.provider.AlarmClock.ACTION_SET_TIMER,
            android.provider.AlarmClock.ACTION_SET_ALARM,
            android.provider.AlarmClock.ACTION_SHOW_ALARMS,
            android.content.Intent.ACTION_INSERT,
            "com.google.android.gms.actions.CREATE_NOTE"
        )

        for (action in required) {
            assertTrue("catalog is missing SDK action '$action'", actions.contains(action))
        }
    }

    @Test
    fun `template action domains map to the taxonomy domains`() {
        val map = schema().templateActionDomains
        assertEquals(IntentTaxonomy.Domains.MAPS, map[AppRegistry.TemplateActions.NAVIGATE])
        assertEquals(IntentTaxonomy.Domains.AUDIO, map[AppRegistry.TemplateActions.SEARCH])
        assertEquals(IntentTaxonomy.Domains.MESSAGING, map[AppRegistry.TemplateActions.SEND])
    }

    @Test
    fun `every templateAction is one the domain map knows`() {
        // probeMetadata maps templateAction -> domain via templateActionDomains; an
        // unknown templateAction would silently contribute no domain.
        val known = schema().templateActionDomains.keys
        for (def in schema().intents) {
            val ta = def.templateAction ?: continue
            assertTrue("intent '${def.label}' uses unknown templateAction '$ta'", known.contains(ta))
        }
    }

    @Test
    fun `entries feeding probeMetadata carry both a templateAction and a uri template`() {
        // probeMetadata only records a domain/uriTemplate when BOTH are present. Assert
        // at least the core navigation route (geo:) is intact so routing can't silently break.
        val navTemplates = schema().intents
            .filter { it.templateAction == AppRegistry.TemplateActions.NAVIGATE && !it.uriTemplate.isNullOrBlank() }
            .map { it.uriTemplate }
        assertTrue("no navigate uri_template present", navTemplates.isNotEmpty())
        assertTrue("geo: navigate template missing", navTemplates.any { it!!.startsWith("geo:") })
    }
}
