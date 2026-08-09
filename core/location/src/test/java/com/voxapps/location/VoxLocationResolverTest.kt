package com.voxapps.location

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeVoxLocationStore(
    private var cached: CachedCoordinate? = null,
    private var ttl: LocationCacheTtl = LocationCacheTtl.ONE_DAY,
    private var homeTown: HomeTown? = null,
    private var alwaysUseHomeTown: Boolean = false
) : VoxLocationStore {
    override fun getCachedLocationSync(): CachedCoordinate? = cached
    override fun getCacheTtlSync(): LocationCacheTtl = ttl
    override fun getHomeTownSync(): HomeTown? = homeTown
    override fun getAlwaysUseHomeTownSync(): Boolean = alwaysUseHomeTown

    override suspend fun setCachedLocation(coord: CachedCoordinate?) {
        cached = coord
    }

    override suspend fun setCacheTtl(ttl: LocationCacheTtl) {
        this.ttl = ttl
    }

    override suspend fun setHomeTown(homeTown: HomeTown?) {
        this.homeTown = homeTown
    }

    override suspend fun setAlwaysUseHomeTown(enabled: Boolean) {
        alwaysUseHomeTown = enabled
    }
}

private class FakeLiveLocationProvider(
    private val lastKnown: LiveFix? = null,
    private val freshFix: LiveFix? = null
) : LiveLocationProvider {
    var lastKnownCalls = 0
        private set
    var freshFixCalls = 0
        private set

    override fun getLastKnownLocation(): LiveFix? {
        lastKnownCalls++
        return lastKnown
    }

    override suspend fun requestFreshFix(timeoutMillis: Long): LiveFix? {
        freshFixCalls++
        return freshFix
    }
}

/** Tests for [VoxLocationResolver]'s priority chain: always-use-Home-Town short-circuit, then
 *  live beats fresh cache beats Home Town — see the class doc comment for the exact order. */
class VoxLocationResolverTest {

    @Test
    fun `always-use-home-town short-circuits before any GPS attempt`() = runTest {
        val store = FakeVoxLocationStore(
            homeTown = HomeTown(1.0, 2.0),
            alwaysUseHomeTown = true,
            cached = CachedCoordinate(9.0, 9.0, System.currentTimeMillis())
        )
        val liveProvider = FakeLiveLocationProvider(lastKnown = LiveFix(5.0, 5.0))
        val resolver = VoxLocationResolver(store, liveProvider)

        val result = resolver.resolveLocation()

        assertEquals(ResolvedLocation(1.0, 2.0, LocationSource.HOME_TOWN), result)
        assertEquals(0, liveProvider.lastKnownCalls)
        assertEquals(0, liveProvider.freshFixCalls)
    }

    @Test
    fun `live fix beats fresh cache and refreshes it`() = runTest {
        val store = FakeVoxLocationStore(
            cached = CachedCoordinate(9.0, 9.0, System.currentTimeMillis()),
            homeTown = HomeTown(1.0, 2.0)
        )
        val liveProvider = FakeLiveLocationProvider(lastKnown = LiveFix(5.0, 6.0))
        val resolver = VoxLocationResolver(store, liveProvider)

        val result = resolver.resolveLocation()

        assertEquals(5.0, result?.lat)
        assertEquals(6.0, result?.lon)
        assertEquals(LocationSource.LIVE, result?.source)
        assertEquals(5.0, store.getCachedLocationSync()?.lat)
    }

    @Test
    fun `no live fix falls back to fresh cache`() = runTest {
        val cachedAt = System.currentTimeMillis()
        val store = FakeVoxLocationStore(
            cached = CachedCoordinate(9.0, 9.0, cachedAt, resolvedName = "Testville"),
            ttl = LocationCacheTtl.FOREVER,
            homeTown = HomeTown(1.0, 2.0)
        )
        val liveProvider = FakeLiveLocationProvider(lastKnown = null, freshFix = null)
        val resolver = VoxLocationResolver(store, liveProvider)

        val result = resolver.resolveLocation()

        assertEquals(ResolvedLocation(9.0, 9.0, LocationSource.CACHE, "Testville"), result)
    }

    @Test
    fun `expired cache falls through to Home Town`() = runTest {
        val staleTimestamp = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(2)
        val store = FakeVoxLocationStore(
            cached = CachedCoordinate(9.0, 9.0, staleTimestamp),
            ttl = LocationCacheTtl.ONE_DAY,
            homeTown = HomeTown(1.0, 2.0)
        )
        val liveProvider = FakeLiveLocationProvider(lastKnown = null, freshFix = null)
        val resolver = VoxLocationResolver(store, liveProvider)

        val result = resolver.resolveLocation()

        assertEquals(ResolvedLocation(1.0, 2.0, LocationSource.HOME_TOWN), result)
    }

    @Test
    fun `nothing available returns null`() = runTest {
        val store = FakeVoxLocationStore()
        val liveProvider = FakeLiveLocationProvider(lastKnown = null, freshFix = null)
        val resolver = VoxLocationResolver(store, liveProvider)

        assertNull(resolver.resolveLocation())
    }

    @Test
    fun `toggling always-use-home-town on clears the cache`() = runTest {
        val store = FakeVoxLocationStore(cached = CachedCoordinate(9.0, 9.0, System.currentTimeMillis()))

        VoxLocationResolver.setAlwaysUseHomeTown(store, true)

        assertTrue(store.getAlwaysUseHomeTownSync())
        assertNull(store.getCachedLocationSync())
    }

    @Test
    fun `toggling always-use-home-town off does not touch the cache`() = runTest {
        val cached = CachedCoordinate(9.0, 9.0, System.currentTimeMillis())
        val store = FakeVoxLocationStore(cached = cached, alwaysUseHomeTown = true)

        VoxLocationResolver.setAlwaysUseHomeTown(store, false)

        assertFalse(store.getAlwaysUseHomeTownSync())
        assertEquals(cached, store.getCachedLocationSync())
    }
}
