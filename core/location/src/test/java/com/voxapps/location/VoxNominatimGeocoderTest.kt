package com.voxapps.location

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxNominatimGeocoderTest {

    @Test
    fun `folding is case-insensitive`() {
        assertEquals("ploiesti", VoxNominatimGeocoder.foldQuery("PLOIESTI"))
    }

    @Test
    fun `s-comma and s-cedilla fold to the same query`() {
        // Romanian keyboards produce U+015F (ş, cedilla); OpenStreetMap writes U+0219 (ș, comma).
        assertEquals(
            VoxNominatimGeocoder.foldQuery("Ploieşti"),
            VoxNominatimGeocoder.foldQuery("Ploiești")
        )
        assertEquals("ploiesti", VoxNominatimGeocoder.foldQuery("Ploiești"))
    }

    @Test
    fun `all combining marks are stripped`() {
        assertEquals("timisoara", VoxNominatimGeocoder.foldQuery("Timișoara"))
        assertEquals("munchen", VoxNominatimGeocoder.foldQuery("München"))
        assertEquals("besancon", VoxNominatimGeocoder.foldQuery("Besançon"))
    }

    @Test
    fun `plain ascii queries pass through unchanged except case`() {
        assertEquals("cluj-napoca", VoxNominatimGeocoder.foldQuery("Cluj-Napoca"))
    }
}
