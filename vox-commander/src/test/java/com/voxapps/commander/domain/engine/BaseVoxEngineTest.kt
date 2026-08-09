package com.voxapps.commander.domain.engine

import android.util.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The properties [BaseVoxEngine] exists to guarantee, none of which an engine can opt out of because
 * the orchestration is final.
 */
class BaseVoxEngineTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        mockkObject(com.voxapps.logging.Logger)
        every { com.voxapps.logging.Logger.log(any(), any()) } returns Unit
    }

    private fun spec(id: String = "m1") =
        ModelSpec.LocalModel(id, File("/tmp/$id"), "en")

    private class FakeEngine(
        private val result: Boolean = true,
        private val gate: CompletableDeferred<Unit>? = null,
        private val thrown: Throwable? = null
    ) : BaseVoxEngine() {
        override val engineKey = "fake"
        val loads = AtomicInteger()
        val unloads = AtomicInteger()

        override suspend fun onLoad(spec: ModelSpec): Boolean {
            loads.incrementAndGet()
            gate?.await()
            thrown?.let { throw it }
            return result
        }

        override fun onUnload() { unloads.incrementAndGet() }

        suspend fun useModel(block: suspend () -> Unit) = withModel(block)
        fun publishProgress(f: Float) = reportProgress(f)
    }

    @Test
    fun `a successful load ends in Ready carrying the spec`() = runTest {
        val engine = FakeEngine()
        val s = spec()

        assertTrue(engine.load(s))

        assertEquals(EngineState.Ready(s, (engine.state.value as EngineState.Ready).loadedAt), engine.state.value)
    }

    @Test
    fun `loading the same spec again does not reload`() = runTest {
        val engine = FakeEngine()

        engine.load(spec())
        engine.load(spec())

        assertEquals("the model is already the one requested", 1, engine.loads.get())
        assertEquals(0, engine.unloads.get())
    }

    @Test
    fun `loading a different spec releases the previous model first`() = runTest {
        val engine = FakeEngine()

        engine.load(spec("m1"))
        engine.load(spec("m2"))

        assertEquals(2, engine.loads.get())
        assertEquals("the old model must not be left loaded", 1, engine.unloads.get())
    }

    @Test
    fun `concurrent loads of the same spec load once`() = runTest {
        // The property VoskSttEngine lacks today: its check-then-act has no lock, so two callers can
        // both observe "not loaded" and both load.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)
        val s = spec()

        val racers = List(4) { async { engine.load(s) } }
        gate.complete(Unit)
        val results = racers.awaitAll()

        assertTrue(results.all { it })
        assertEquals(1, engine.loads.get())
    }

    @Test
    fun `a load that returns false ends in Failed and does not claim a model`() = runTest {
        val engine = FakeEngine(result = false)
        val s = spec()

        assertFalse(engine.load(s))

        val state = engine.state.value
        assertTrue(state is EngineState.Failed)
        assertEquals(s, (state as EngineState.Failed).spec)
    }

    @Test
    fun `a load that throws is reported as Failed rather than escaping`() = runTest {
        val engine = FakeEngine(thrown = IllegalStateException("native init failed"))

        assertFalse(engine.load(spec()))

        assertEquals("native init failed", (engine.state.value as EngineState.Failed).reason)
    }

    @Test
    fun `a cancelled load leaves the engine Idle instead of Failed`() = runTest {
        // The caller walking away is not the engine failing. Recording Failed would make the next
        // caller believe the model is broken.
        val engine = FakeEngine(thrown = kotlinx.coroutines.CancellationException("caller gone"))

        runCatching { engine.load(spec()) }

        assertEquals(EngineState.Idle, engine.state.value)
    }

    @Test
    fun `unload while the model is in use is skipped, not deferred silently into a crash`() = runTest {
        val engine = FakeEngine()
        engine.load(spec())

        engine.useModel { engine.unload() }

        assertEquals("must not tear down under a running inference", 0, engine.unloads.get())
        assertTrue(engine.state.value is EngineState.Ready)
    }

    @Test
    fun `unload after use completes releases the model and returns to Idle`() = runTest {
        val engine = FakeEngine()
        engine.load(spec())

        engine.useModel { }
        engine.unload()

        assertEquals(1, engine.unloads.get())
        assertEquals(EngineState.Idle, engine.state.value)
    }

    @Test
    fun `memory pressure is the same operation as unload`() = runTest {
        val engine = FakeEngine()
        engine.load(spec())

        engine.releaseForMemoryPressure()

        assertEquals(1, engine.unloads.get())
        assertEquals(EngineState.Idle, engine.state.value)
    }

    @Test
    fun `progress is only reported while loading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)

        val loading = async { engine.load(spec()) }
        testScheduler.advanceUntilIdle()   // let load() run as far as the gate
        engine.publishProgress(0.5f)
        assertEquals(0.5f, (engine.state.value as EngineState.Loading).progress)

        gate.complete(Unit)
        loading.await()

        engine.publishProgress(0.9f)
        assertTrue("a Ready engine has no progress to report", engine.state.value is EngineState.Ready)
    }
}
