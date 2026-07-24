package com.voxapps.expenses.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MerchantVendorKeyTest {

    @Test
    fun `trims and lowercases the vendor name`() {
        assertEquals("lidl", MerchantVendorKey.normalize("  Lidl  "))
    }

    @Test
    fun `returns null for a null vendor`() {
        assertNull(MerchantVendorKey.normalize(null))
    }

    @Test
    fun `returns null for a blank vendor`() {
        assertNull(MerchantVendorKey.normalize("   "))
    }

    @Test
    fun `returns null for an empty vendor`() {
        assertNull(MerchantVendorKey.normalize(""))
    }

    @Test
    fun `different case and surrounding whitespace normalize to the same key`() {
        assertEquals(MerchantVendorKey.normalize("Lidl"), MerchantVendorKey.normalize(" LIDL "))
    }
}
