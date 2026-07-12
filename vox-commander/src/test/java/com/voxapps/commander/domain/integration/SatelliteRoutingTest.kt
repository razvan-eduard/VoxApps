package com.voxapps.commander.domain.integration

import com.voxapps.ipc.VoxAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteRoutingTest {

    private fun app(pkg: String, firstParty: Boolean = false) =
        VoxAppInfo(packageName = pkg, label = pkg, domain = "notes", actions = listOf("create", "read"), isFirstParty = firstParty)

    @Test
    fun `no candidates returns null`() {
        val d = SatelliteRouting.pick(emptyList())
        assertNull(d.packageName)
        assertFalse(d.ambiguous)
    }

    @Test
    fun `single candidate routes to it`() {
        val d = SatelliteRouting.pick(listOf(app("com.a")))
        assertEquals("com.a", d.packageName)
        assertFalse(d.ambiguous)
    }

    @Test
    fun `first-party wins over third-party when no star`() {
        val d = SatelliteRouting.pick(listOf(app("com.open"), app("com.vox", firstParty = true)))
        assertEquals("com.vox", d.packageName)
        assertFalse(d.ambiguous)
    }

    @Test
    fun `star wins over first-party`() {
        val d = SatelliteRouting.pick(
            candidates = listOf(app("com.open"), app("com.vox", firstParty = true)),
            starredPkg = "com.open"
        )
        assertEquals("com.open", d.packageName)
    }

    @Test
    fun `explicit app named wins over everything`() {
        val d = SatelliteRouting.pick(
            candidates = listOf(app("com.open"), app("com.vox", firstParty = true)),
            starredPkg = "com.vox",
            explicitPkg = "com.open"
        )
        assertEquals("com.open", d.packageName)
    }

    @Test
    fun `star that is not a candidate is ignored`() {
        val d = SatelliteRouting.pick(
            candidates = listOf(app("com.vox", firstParty = true)),
            starredPkg = "com.uninstalled"
        )
        assertEquals("com.vox", d.packageName)
    }

    @Test
    fun `two third-party no star routes to first but flags ambiguous`() {
        val d = SatelliteRouting.pick(listOf(app("com.open"), app("com.fast")))
        assertEquals("com.open", d.packageName)
        assertTrue(d.ambiguous)
    }
}
