package com.voxapps.commander.domain.engine

import android.util.Log
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.testutil.TestDataFactory
import com.voxapps.commander.utils.Strings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudDeadlineTest {

    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        settingsRepo = mockk(relaxed = true)
        mockkObject(RemoteModelRegistry)
        every { RemoteModelRegistry.declaredTimeoutSeconds(any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkObject(RemoteModelRegistry)
    }

    private fun withTimeoutSetting(seconds: Int) {
        every { settingsRepo.getSettingsSnapshot() } returns
            TestDataFactory.createAppSettings().copy(offlineFallbackTimeout = seconds)
    }

    @Test
    fun `an engine that declares nothing gets the user's setting`() {
        withTimeoutSetting(15)

        assertEquals(15, CloudDeadline.secondsFor(Strings.AiProcessors.OPENAI, settingsRepo))
    }

    @Test
    fun `an engine that declares its own budget overrides the setting`() {
        withTimeoutSetting(10)
        every { RemoteModelRegistry.declaredTimeoutSeconds("WHISPER_API") } returns 45

        // Uploading audio is not the same call as a short text prompt, which is the whole reason a
        // single number is too blunt to be the only one.
        assertEquals(45, CloudDeadline.secondsFor("WHISPER_API", settingsRepo))
    }

    @Test
    fun `a setting below the floor cannot disable the engine outright`() {
        withTimeoutSetting(1)

        assertEquals(3, CloudDeadline.secondsFor(Strings.AiProcessors.OPENAI, settingsRepo))
    }

    @Test
    fun `a call that outlives its deadline gives the caller null`() = runTest {
        withTimeoutSetting(10)

        // No network here: the point under test is that the *caller* is released on time, which is
        // what lets the cascade move on to the user's fallback. Releasing the socket is the
        // interceptor's half of the contract.
        val result = CloudDeadline.run(Strings.AiProcessors.OPENAI, settingsRepo) {
            delay(60_000)
            "answered eventually"
        }

        assertNull(result)
    }

    @Test
    fun `a call that answers within the deadline is passed straight through`() = runTest {
        withTimeoutSetting(10)

        val result = CloudDeadline.run(Strings.AiProcessors.OPENAI, settingsRepo) {
            delay(1_000)
            "answered"
        }

        assertEquals("answered", result)
    }
}
