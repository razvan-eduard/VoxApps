package com.voxapps.commander

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voxapps.commander.data.remote.NativeLibManager
import com.voxapps.nativelibs.NativeLibs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device/emulator, not the JVM — this is the only kind of test that can catch a
 * native-linking regression like onnxruntime-android 1.27.0/1.28.0's arm64-v8a build, which
 * compiled and passed unit tests cleanly but threw UnsatisfiedLinkError ("cannot locate symbol
 * OrtGetApiBase") the moment the real device linker resolved it. NativeLibManager.loadAll() only
 * catches Exception (UnsatisfiedLinkError is an Error, not an Exception), so a genuine regression
 * here surfaces as an uncaught throwable that fails this test, exactly as intended. See
 * docs/BUILD_TIME_DEPENDENCIES.md's onnxruntime-android section for the incident this guards
 * against.
 */
@RunWith(AndroidJUnit4::class)
class NativeLibManagerInstrumentedTest {

    @Test
    fun essentialNativeLibsLoadSuccessfully() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeLibManager.init(context)
        assertEquals(NativeLibs.Status.READY, NativeLibManager.status.value)
    }
}
