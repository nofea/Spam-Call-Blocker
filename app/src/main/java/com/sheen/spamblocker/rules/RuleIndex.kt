package com.sheen.spamblocker.rules

import com.sheen.spamblocker.screening.Stage

/** What matched: the stage, the action (null for allow rules) and the rule or source it came from. */
data class RuleMatch(
    val stage: Stage,
    val action: Action?,
    val ruleId: Long? = null,
    val sourceId: Long? = null,
)

data class IndexEntry(val pattern: CompiledPattern, val match: RuleMatch)

/**
 * Immutable in-memory rule lookup (SPEC §10): one [PatternSet] for block rules
 * and list entries, one for allow rules. Numbers are E.164 strings.
 */
class RuleIndex(private val block: PatternSet, private val allow: PatternSet) {

    fun allowMatch(e164: String): RuleMatch? = allow.bestMatch(e164.removePrefix("+"))

    fun blockMatch(e164: String): RuleMatch? = block.bestMatch(e164.removePrefix("+"))

    val blockEntryCount: Int get() = block.size
    val allowEntryCount: Int get() = allow.size

    companion object {
        val EMPTY = RuleIndex(PatternSet.build(emptyList()), PatternSet.build(emptyList()))

        fun build(blockEntries: List<IndexEntry>, allowEntries: List<IndexEntry>) =
            RuleIndex(PatternSet.build(blockEntries), PatternSet.build(allowEntries))
    }
}

/**
 * A set of patterns that returns the best match for a number: the earliest
 * [Stage] wins, then the more specific pattern (more literal digits), then the
 * entry that was added first.
 *
 * - Exact numbers live in sorted primitive arrays (binary search), so a
 *   200k-entry list costs a few MB rather than millions of trie nodes.
 * - Prefix and `?` patterns live in an array-backed digit trie with a `?` edge.
 * - Suffix patterns are a short linear list (SPEC §6.1).
 */
class PatternSet private constructor(
    private val exactKeys: LongArray,
    private val exactMatches: Array<RuleMatch>,
    private val exactOrders: IntArray,
    private val trie: DigitTrie,
    private val suffixes: List<IndexEntry>,
    val size: Int,
) {
    fun bestMatch(number: String): RuleMatch? {
        if (number.isEmpty() || number.length > WildcardCompiler.MAX_DIGITS || !number.all(Char::isDigit)) return null
        var best: Candidate? = null
        fun offer(c: Candidate) {
            if (best == null || c.betterThan(best!!)) best = c
        }

        val key = exactKey(number)
        val i = lowerBound(exactKeys, key)
        if (i < exactKeys.size && exactKeys[i] == key) {
            offer(Candidate(exactMatches[i], number.length, exactOrders[i]))
        }
        trie.collect(number, ::offer)
        suffixes.forEachIndexed { order, entry ->
            if (entry.pattern.matches(number)) offer(Candidate(entry.match, entry.pattern.specificity, order))
        }
        return best?.match
    }

    internal class Candidate(val match: RuleMatch, val specificity: Int, val order: Int) {
        fun betterThan(other: Candidate): Boolean {
            if (match.stage != other.match.stage) return match.stage < other.match.stage
            if (specificity != other.specificity) return specificity > other.specificity
            return order < other.order
        }
    }

    companion object {
        fun build(entries: List<IndexEntry>): PatternSet {
            val exact = ArrayList<IndexedValue<IndexEntry>>()
            val trie = DigitTrie.Builder()
            val suffixes = ArrayList<IndexEntry>()
            entries.forEachIndexed { order, entry ->
                val p = entry.pattern
                when {
                    p is CompiledPattern.Suffix -> suffixes += entry
                    p is CompiledPattern.Exact && !p.hasSingleDigitWildcard -> exact += IndexedValue(order, entry)
                    else -> trie.add(p, entry.match, order)
                }
            }
            // Sort by key, then by priority, so the first hit for a key is the best one.
            exact.sortWith(
                compareBy<IndexedValue<IndexEntry>> { exactKey(it.value.pattern.digits) }
                    .thenBy { it.value.match.stage }
                    .thenBy { it.index },
            )
            val keys = ArrayList<Long>(exact.size)
            val matches = ArrayList<RuleMatch>(exact.size)
            val orders = ArrayList<Int>(exact.size)
            for ((order, entry) in exact) {
                val key = exactKey(entry.pattern.digits)
                if (keys.isNotEmpty() && keys.last() == key) continue
                keys += key
                matches += entry.match
                orders += order
            }
            return PatternSet(
                keys.toLongArray(), matches.toTypedArray(), orders.toIntArray(), trie.build(), suffixes, entries.size,
            )
        }

        /**
         * Packs up to 15 digits and the length into a Long, so `0123` and `123`
         * stay distinct.
         */
        private fun exactKey(digits: String): Long {
            var value = 0L
            for (c in digits) value = value * 10 + (c - '0')
            return value * 16 + digits.length
        }

        private fun lowerBound(keys: LongArray, key: Long): Int {
            var lo = 0
            var hi = keys.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (keys[mid] < key) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

/**
 * Digit trie in flat arrays. Each node has 11 child slots (digits 0–9 plus
 * `?`) and two terminal slots: one for prefix patterns (match any longer
 * number) and one for exact-length patterns.
 */
internal class DigitTrie private constructor(
    private val children: IntArray,
    private val prefixTerminal: IntArray,
    private val exactTerminal: IntArray,
    private val terminals: Array<PatternSet.Candidate>,
) {
    fun collect(number: String, sink: (PatternSet.Candidate) -> Unit) {
        if (prefixTerminal.isEmpty()) return
        walk(0, number, 0, sink)
    }

    private fun walk(node: Int, number: String, pos: Int, sink: (PatternSet.Candidate) -> Unit) {
        prefixTerminal[node].takeIf { it >= 0 }?.let { sink(terminals[it]) }
        if (pos == number.length) {
            exactTerminal[node].takeIf { it >= 0 }?.let { sink(terminals[it]) }
            return
        }
        val digitChild = children[node * FANOUT + (number[pos] - '0')]
        if (digitChild > 0) walk(digitChild, number, pos + 1, sink)
        val anyChild = children[node * FANOUT + ANY]
        if (anyChild > 0) walk(anyChild, number, pos + 1, sink)
    }

    class Builder {
        private var children = IntArray(FANOUT * 64)
        private var prefixTerminal = IntArray(64) { -1 }
        private var exactTerminal = IntArray(64) { -1 }
        private var nodeCount = 1
        private val terminals = ArrayList<PatternSet.Candidate>()

        fun add(pattern: CompiledPattern, match: RuleMatch, order: Int) {
            var node = 0
            for (c in pattern.digits) {
                val slot = node * FANOUT + if (c == '?') ANY else c - '0'
                if (children[slot] == 0) children[slot] = newNode()
                node = children[slot]
            }
            val slots = if (pattern is CompiledPattern.Prefix) prefixTerminal else exactTerminal
            val candidate = PatternSet.Candidate(match, pattern.specificity, order)
            val existing = slots[node]
            if (existing < 0) {
                slots[node] = terminals.size
                terminals += candidate
            } else if (candidate.betterThan(terminals[existing])) {
                terminals[existing] = candidate
            }
        }

        private fun newNode(): Int {
            if (nodeCount == prefixTerminal.size) {
                val capacity = nodeCount * 2
                children = children.copyOf(capacity * FANOUT)
                prefixTerminal = prefixTerminal.copyOf(capacity).also { it.fill(-1, nodeCount) }
                exactTerminal = exactTerminal.copyOf(capacity).also { it.fill(-1, nodeCount) }
            }
            return nodeCount++
        }

        fun build(): DigitTrie =
            if (terminals.isEmpty()) {
                DigitTrie(IntArray(0), IntArray(0), IntArray(0), emptyArray())
            } else {
                DigitTrie(
                    children.copyOf(nodeCount * FANOUT),
                    prefixTerminal.copyOf(nodeCount),
                    exactTerminal.copyOf(nodeCount),
                    terminals.toTypedArray(),
                )
            }
    }

    private companion object {
        const val FANOUT = 11
        const val ANY = 10
    }
}
