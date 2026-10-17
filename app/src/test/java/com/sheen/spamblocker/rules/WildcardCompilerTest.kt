package com.sheen.spamblocker.rules

import com.sheen.spamblocker.rules.CompiledPattern.Exact
import com.sheen.spamblocker.rules.CompiledPattern.Prefix
import com.sheen.spamblocker.rules.CompiledPattern.Suffix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WildcardCompilerTest {
    private val compiler = WildcardCompiler()

    @Test
    fun `valid syntax from SPEC 6_1`() {
        assertEquals(Prefix("9198765"), compiler.compile("+9198765*"))
        assertEquals(Exact("91987654321?"), compiler.compile("+91987654321?"))
        assertEquals(Suffix("4321"), compiler.compile("*4321"))
        assertEquals(Prefix("91140"), compiler.compile("+91140*"))
        assertEquals(Exact("919876543210"), compiler.compile("+919876543210"))
    }

    @Test
    fun `national input is normalized to E164`() {
        assertEquals(Prefix("9198765"), compiler.compile("98765*"))
        assertEquals(Prefix("9198765"), compiler.compile("098765*"))
        assertEquals(Exact("91987654321?"), compiler.compile("987654321?"))
        assertEquals(Exact("919876543210"), compiler.compile("098765 43210"))
        assertEquals(Exact("919876543210"), compiler.compile("919876543210"))
        assertEquals(Prefix("9198765"), compiler.compile("+91-98765 *"))
    }

    @Test
    fun `invalid syntax is rejected`() {
        listOf(
            "", "*", "+", "+*", "abc", "+91*98*", "+91*765", "*+91", "+9+1*", "+0123*",
            "+1234567890123456", "????",
        ).forEach { input ->
            assertThrows(input, InvalidPatternException::class.java) { compiler.compile(input) }
        }
    }

    @Test
    fun `coverage sizes`() {
        assertEquals(1L, WildcardCompiler.coverage(compiler.compile("+919876543210")))
        assertEquals(10L, WildcardCompiler.coverage(compiler.compile("+91987654321?")))
        assertEquals(100_000L, WildcardCompiler.coverage(compiler.compile("+9198765*")))
        assertEquals(100_000_000L, WildcardCompiler.coverage(compiler.compile("*4321")))
        assertEquals(10_000_000L, WildcardCompiler.coverage(compiler.compile("+91140*")))
        assertTrue(WildcardCompiler.coverage(compiler.compile("+91*")) > WildcardCompiler.COVERAGE_WARNING_THRESHOLD)
    }

    @Test
    fun `linear matching`() {
        assertTrue(compiler.compile("+91987654321?").matches("919876543219"))
        assertFalse(compiler.compile("+91987654321?").matches("9198765432190"))
        assertTrue(compiler.compile("*43?1").matches("919876544301"))
        assertTrue(compiler.compile("+9198765*").matches("919876500000"))
        assertFalse(compiler.compile("+9198765*").matches("919876600000"))
    }

    @Test
    fun `canonical text round-trips`() {
        listOf("+9198765*", "+91987654321?", "*4321", "+919876543210").forEach {
            assertEquals(it, compiler.compile(it).text)
        }
    }
}
