package com.sheen.spamblocker.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefixDeriverTest {
    private val deriver = PrefixDeriver()

    private val cluster = listOf("+919876543201", "+919876543215", "+919876543299")

    @Test
    fun `K numbers sharing an L-digit prefix give a suggestion`() {
        val result = deriver.derive(cluster)
        assertEquals(1, result.size)
        assertEquals("+9198765432", result.single().prefix)
        assertEquals(100L, result.single().rangeSize)
        assertEquals(cluster, result.single().sampleNumbers)
    }

    @Test
    fun `fewer than K numbers give nothing`() {
        assertTrue(deriver.derive(cluster.take(2)).isEmpty())
        assertEquals(1, PrefixDeriver(minCount = 2).derive(cluster.take(2)).size)
    }

    @Test
    fun `L sets the shortest prefix`() {
        val spread = listOf("+919876501000", "+919876502000", "+919876509000")
        assertTrue(deriver.derive(spread).isEmpty())
        assertEquals("+91987650", PrefixDeriver(minPrefixDigits = 6).derive(spread).single().prefix)
    }

    @Test
    fun `the longest shared prefix is preferred`() {
        val tight = listOf("+919876543211", "+919876543212", "+919876543213", "+919876543299")
        val result = deriver.derive(tight)
        assertEquals(listOf("+91987654321"), result.map { it.prefix })
        assertEquals(10L, result.single().rangeSize)
    }

    @Test
    fun `exclusions`() {
        assertTrue("contact", deriver.derive(cluster, protectedNumbers = listOf("+919876543250")).isEmpty())
        assertTrue("allowlist prefix", deriver.derive(cluster, allowPrefixes = listOf("+91987654")).isEmpty())
        assertTrue("narrower allowlist prefix", deriver.derive(cluster, allowPrefixes = listOf("+919876543250")).isEmpty())
        assertTrue("existing rule", deriver.derive(cluster, existingBlockPrefixes = listOf("+91987654")).isEmpty())
        assertTrue("dismissed", deriver.derive(cluster, dismissedPrefixes = listOf("+9198765432")).isEmpty())
        assertEquals(1, deriver.derive(cluster, protectedNumbers = listOf("+919876549999")).size)
    }

    @Test
    fun `only Indian 10-digit non-TRAI numbers count`() {
        assertTrue(deriver.derive(listOf("+16502530001", "+16502530002", "+16502530003")).isEmpty())
        assertTrue(deriver.derive(listOf("+911401234501", "+911401234502", "+911401234503")).isEmpty())
    }

    @Test
    fun `K and L bounds are enforced`() {
        assertThrows(IllegalArgumentException::class.java) { PrefixDeriver(minCount = 1) }
        assertThrows(IllegalArgumentException::class.java) { PrefixDeriver(minPrefixDigits = 5) }
        assertThrows(IllegalArgumentException::class.java) { PrefixDeriver(minPrefixDigits = 10) }
    }
}
