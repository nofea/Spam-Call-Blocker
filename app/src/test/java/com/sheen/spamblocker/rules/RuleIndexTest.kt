package com.sheen.spamblocker.rules

import com.sheen.spamblocker.screening.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleIndexTest {
    private val compiler = WildcardCompiler()

    private fun block(pattern: String, stage: Stage, id: Long, action: Action = Action.REJECT) =
        IndexEntry(compiler.compile(pattern), RuleMatch(stage, action, ruleId = id))

    private fun source(pattern: String, sourceId: Long, action: Action = Action.SILENCE) =
        IndexEntry(compiler.compile(pattern), RuleMatch(Stage.REMOTE_LIST, action, sourceId = sourceId))

    @Test
    fun `exact, prefix, wildcard and suffix matches`() {
        val index = RuleIndex.build(
            listOf(
                block("+919876543210", Stage.USER_BLOCK, 1),
                block("+9188888*", Stage.PATTERN, 2),
                block("+91777777777?", Stage.PATTERN, 3),
                block("*4321", Stage.PATTERN, 4),
            ),
            emptyList(),
        )
        assertEquals(1L, index.blockMatch("+919876543210")?.ruleId)
        assertEquals(2L, index.blockMatch("+918888812345")?.ruleId)
        assertEquals(3L, index.blockMatch("+917777777775")?.ruleId)
        assertNull(index.blockMatch("+9177777777750"))
        assertEquals(4L, index.blockMatch("+915555554321")?.ruleId)
        assertNull(index.blockMatch("+915555555555"))
        assertNull(index.blockMatch("+919876543211"))
    }

    @Test
    fun `earliest stage wins when several rules match`() {
        val index = RuleIndex.build(
            listOf(
                source("+919876543210", sourceId = 7),
                block("+9198765*", Stage.DERIVED_PREFIX, 2, Action.SILENCE),
                block("*3210", Stage.PATTERN, 3, Action.WARN),
                block("+919876543210", Stage.USER_BLOCK, 1),
            ),
            emptyList(),
        )
        assertEquals(RuleMatch(Stage.USER_BLOCK, Action.REJECT, ruleId = 1), index.blockMatch("+919876543210"))
        assertEquals(Stage.PATTERN, index.blockMatch("+919876593210")?.stage)
        assertEquals(Stage.DERIVED_PREFIX, index.blockMatch("+919876511111")?.stage)
    }

    @Test
    fun `within a stage the more specific pattern wins`() {
        val index = RuleIndex.build(
            listOf(
                block("+9198*", Stage.PATTERN, 1, Action.WARN),
                block("+9198765*", Stage.PATTERN, 2, Action.REJECT),
                block("+91987654321?", Stage.PATTERN, 3, Action.SILENCE),
            ),
            emptyList(),
        )
        assertEquals(1L, index.blockMatch("+919811111111")?.ruleId)
        assertEquals(2L, index.blockMatch("+919876511111")?.ruleId)
        assertEquals(3L, index.blockMatch("+919876543219")?.ruleId)
    }

    @Test
    fun `duplicate exact numbers keep the best match`() {
        val index = RuleIndex.build(
            listOf(source("+919876543210", 2), source("+919876543210", 1), block("+919876543210", Stage.USER_BLOCK, 9)),
            emptyList(),
        )
        assertEquals(9L, index.blockMatch("+919876543210")?.ruleId)
    }

    @Test
    fun `allow rules are separate from block rules`() {
        val allow = IndexEntry(compiler.compile("+9180*"), RuleMatch(Stage.ALLOWLIST, null, ruleId = 5))
        val index = RuleIndex.build(listOf(block("+918012345678", Stage.USER_BLOCK, 1)), listOf(allow))
        assertEquals(5L, index.allowMatch("+918012345678")?.ruleId)
        assertEquals(1L, index.blockMatch("+918012345678")?.ruleId)
        assertNull(index.allowMatch("+919012345678"))
    }

    @Test
    fun `unparsed and junk numbers don't crash`() {
        val index = RuleIndex.build(listOf(block("+123*", Stage.PATTERN, 1)), emptyList())
        assertNull(index.blockMatch(""))
        assertNull(index.blockMatch("+"))
        assertNull(index.blockMatch("+12a4"))
        assertNull(index.blockMatch("+1234567890123456789"))
        assertEquals(1L, index.blockMatch("+1234")?.ruleId)
    }

    @Test
    fun `200k exact entries build and look up quickly`() {
        val entries = (0 until 200_000).map { i ->
            IndexEntry(CompiledPattern.Exact("91${9_000_000_000L + i * 7}"), RuleMatch(Stage.REMOTE_LIST, Action.SILENCE, sourceId = 1))
        }
        val index = RuleIndex.build(entries, emptyList())
        val start = System.nanoTime()
        repeat(10_000) { i -> index.blockMatch("+91${9_000_000_000L + i * 7}") }
        val perLookupMicros = (System.nanoTime() - start) / 10_000 / 1_000.0
        assertEquals(Stage.REMOTE_LIST, index.blockMatch("+919000000007")?.stage)
        assertNull(index.blockMatch("+919000000008"))
        assert(perLookupMicros < 100) { "lookup took $perLookupMicros µs" }
    }
}
