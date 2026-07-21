package com.voxapps.commander.domain.intent.resolver

import android.util.Log
import com.voxapps.commander.data.preferences.AppAliasRule
import com.voxapps.commander.data.preferences.AppSettings
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.intent.taxonomy.IntentTaxonomy
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.logging.Logger
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AppResolver.resolve] — the app-resolution chain:
 * explicit targetApp → alias rule → user default per domain → domain default → null.
 */
class AppResolverTest {

    private val spotify = "com.spotify.music"
    private val maps = "com.google.android.apps.maps"
    private val newpipe = "org.schabi.newpipe"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        mockkObject(Logger)
        every { Logger.log(any(), any()) } returns Unit

        // Populate the AppRegistry singleton via its cache seam. Spotify is listed
        // before NewPipe so it is the *domain default* for "audio".
        val cacheJson = """
            [
              {"packageName":"$spotify","displayName":"Spotify","domains":["audio"],"uriTemplates":{},"isSystemApp":false},
              {"packageName":"$maps","displayName":"Maps","domains":["maps"],"uriTemplates":{},"isSystemApp":false},
              {"packageName":"$newpipe","displayName":"NewPipe","domains":["audio"],"uriTemplates":{},"isSystemApp":false}
            ]
        """.trimIndent()
        assertEquals(true, AppRegistry.initFromCache(cacheJson))
    }

    @Test
    fun `resolves explicit targetApp by package name`() {
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.AUDIO, targetApp = spotify)
        assertEquals(spotify, AppResolver.resolve(intent, AppSettings())?.packageName)
    }

    @Test
    fun `resolves explicit targetApp by display name`() {
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.MAPS, targetApp = "Maps")
        assertEquals(maps, AppResolver.resolve(intent, AppSettings())?.packageName)
    }

    @Test
    fun `resolves via enabled alias rule`() {
        val settings = AppSettings(
            appAliasRules = listOf(
                AppAliasRule(id = "1", packageName = newpipe, displayName = "NewPipe", aliases = listOf("yt"))
            )
        )
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.AUDIO, targetApp = "yt")
        assertEquals(newpipe, AppResolver.resolve(intent, settings)?.packageName)
    }

    @Test
    fun `disabled alias rule is ignored and falls through to domain default`() {
        val settings = AppSettings(
            appAliasRules = listOf(
                AppAliasRule(id = "1", packageName = newpipe, displayName = "NewPipe", aliases = listOf("yt"), enabled = false)
            )
        )
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.AUDIO, targetApp = "yt")
        // "yt" resolves by neither package nor name, alias is disabled → domain default (Spotify).
        assertEquals(spotify, AppResolver.resolve(intent, settings)?.packageName)
    }

    @Test
    fun `user default for domain wins over domain default`() {
        val settings = AppSettings(defaultAppPackages = mapOf(IntentTaxonomy.Domains.AUDIO to newpipe))
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.AUDIO, targetApp = null)
        assertEquals(newpipe, AppResolver.resolve(intent, settings)?.packageName)
    }

    @Test
    fun `falls back to domain default when no target and no user default`() {
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.AUDIO, targetApp = null)
        assertEquals(spotify, AppResolver.resolve(intent, AppSettings())?.packageName)
    }

    @Test
    fun `returns null for domain with no installed apps`() {
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.MESSAGING, targetApp = null)
        assertNull(AppResolver.resolve(intent, AppSettings()))
    }

    @Test
    fun `explicit targetApp beats user default`() {
        val settings = AppSettings(defaultAppPackages = mapOf(IntentTaxonomy.Domains.AUDIO to newpipe))
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.AUDIO, targetApp = spotify)
        assertEquals(spotify, AppResolver.resolve(intent, settings)?.packageName)
    }

    @Test
    fun `uninstalled targetApp falls through to domain default`() {
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.AUDIO, targetApp = "com.not.installed")
        assertEquals(spotify, AppResolver.resolve(intent, AppSettings())?.packageName)
    }

    @Test
    fun `null settings skips alias and user default`() {
        // Only an alias could route "yt"; with settings=null it must fall to domain default.
        val intent = TestDataFactory.createNluIntent(domain = IntentTaxonomy.Domains.AUDIO, targetApp = "yt")
        assertEquals(spotify, AppResolver.resolve(intent, null)?.packageName)
    }
}
