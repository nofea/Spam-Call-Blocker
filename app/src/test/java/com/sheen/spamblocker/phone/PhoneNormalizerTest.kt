package com.sheen.spamblocker.phone

import com.sheen.spamblocker.rules.TraiSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNormalizerTest {
    private val normalizer = PhoneNormalizer()

    private fun parsed(raw: String) = normalizer.normalize(raw) as NormalizedNumber.Parsed

    @Test
    fun `indian mobile formats normalize to the same E164`() {
        listOf(
            "9876543210",
            "09876543210",
            "919876543210",
            "+91 98765 43210",
            "+91-98765-43210",
        ).forEach { raw ->
            val n = parsed(raw)
            assertEquals(raw, "+919876543210", n.e164)
            assertEquals(raw, "9876543210", n.nationalNumber)
            assertTrue(raw, n.isIndian)
        }
    }

    @Test
    fun `null, blank and digitless handles are hidden`() {
        listOf(null, "", "   ", "Private").forEach { raw ->
            assertEquals(NormalizedNumber.Hidden, normalizer.normalize(raw))
        }
    }

    @Test
    fun `international number is not indian`() {
        val n = parsed("+1 650 253 0000")
        assertEquals("+16502530000", n.e164)
        assertEquals(1, n.countryCode)
        assertFalse(n.isIndian)
    }

    @Test
    fun `TRAI series are detected on the national number`() {
        val promo = parsed("1401234567")
        assertTrue(promo.isIndian)
        assertTrue(TraiSeries.isPromotional(promo.nationalNumber))
        assertFalse(TraiSeries.isTransactional(promo.nationalNumber))

        val txn = parsed("+91 160 012 3456")
        assertTrue(TraiSeries.isTransactional(txn.nationalNumber))
        assertFalse(TraiSeries.isPromotional(txn.nationalNumber))

        assertFalse(TraiSeries.isPromotional(parsed("9876543210").nationalNumber))
    }
}
