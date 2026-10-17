package com.sheen.spamblocker.rules

/** A suggested range (SPEC §6.2). [prefix] is E.164 without `*`, e.g. `+9198765432`. */
data class PrefixCandidate(
    val prefix: String,
    val rangeSize: Long,
    val sampleNumbers: List<String>,
)

/**
 * Finds clusters of blocked Indian numbers that share a national-number prefix
 * (SPEC §6.2). Suggestions only: nothing here blocks anything.
 */
class PrefixDeriver(
    /** K: numbers that must share a prefix. */
    private val minCount: Int = DEFAULT_MIN_COUNT,
    /** L: shortest prefix considered, in national digits. */
    private val minPrefixDigits: Int = DEFAULT_MIN_PREFIX_DIGITS,
) {
    init {
        require(minCount >= 2) { "K must be at least 2" }
        require(minPrefixDigits in 6..MAX_PREFIX_DIGITS) { "L must be between 6 and 9" }
    }

    /**
     * @param blockedNumbers exact numbers on the user block list (E.164).
     * @param protectedNumbers contacts, allowlisted numbers and numbers the user called (E.164).
     * @param allowPrefixes allowlist prefix rules (E.164 without `*`).
     * @param existingBlockPrefixes prefix block rules already in place (E.164 without `*`).
     * @param dismissedPrefixes previously dismissed or already suggested prefixes.
     */
    fun derive(
        blockedNumbers: Collection<String>,
        protectedNumbers: Collection<String> = emptyList(),
        allowPrefixes: Collection<String> = emptyList(),
        existingBlockPrefixes: Collection<String> = emptyList(),
        dismissedPrefixes: Collection<String> = emptyList(),
    ): List<PrefixCandidate> {
        val nsns = blockedNumbers.mapNotNull(::eligibleNsn).distinct()

        // Steps 2–3: every prefix of length L..9 shared by at least K numbers.
        val groups = HashMap<String, MutableList<String>>()
        for (nsn in nsns) {
            for (length in minPrefixDigits..MAX_PREFIX_DIGITS) {
                groups.getOrPut(nsn.take(length)) { mutableListOf() } += nsn
            }
        }
        val candidates = groups.filterValues { it.size >= minCount }

        // Prefer the longest: drop a candidate that has a longer candidate under it.
        val maximal = candidates.keys.filter { prefix ->
            candidates.keys.none { other -> other.length > prefix.length && other.startsWith(prefix) }
        }

        val protectedNsns = protectedNumbers.mapNotNull(::indianNsn)
        val allowNsnPrefixes = allowPrefixes.mapNotNull(::indianNsn)
        val blockNsnPrefixes = existingBlockPrefixes.mapNotNull(::indianNsn)
        val dismissed = dismissedPrefixes.toSet()

        // Step 4: exclusions.
        return maximal
            .filterNot { p -> protectedNsns.any { it.startsWith(p) } }
            .filterNot { p -> allowNsnPrefixes.any { it.startsWith(p) || p.startsWith(it) } }
            .filterNot { p -> blockNsnPrefixes.any { p.startsWith(it) } }
            .filterNot { p -> "$INDIA$p" in dismissed }
            .sorted()
            .map { p ->
                PrefixCandidate(
                    prefix = "$INDIA$p",
                    rangeSize = pow10(NSN_DIGITS - p.length),
                    sampleNumbers = candidates.getValue(p).sorted().map { "$INDIA$it" },
                )
            }
    }

    /** Indian 10-digit mobile or landline numbers, excluding the TRAI service series. */
    private fun eligibleNsn(e164: String): String? =
        indianNsn(e164)?.takeIf {
            it.length == NSN_DIGITS && it.all(Char::isDigit) &&
                !TraiSeries.isPromotional(it) && !TraiSeries.isTransactional(it)
        }

    private fun indianNsn(e164: String): String? = e164.takeIf { it.startsWith(INDIA) }?.removePrefix(INDIA)

    private fun pow10(exp: Int): Long = (1..exp).fold(1L) { acc, _ -> acc * 10 }

    companion object {
        const val DEFAULT_MIN_COUNT = 3
        const val DEFAULT_MIN_PREFIX_DIGITS = 8
        const val MAX_PREFIX_DIGITS = 9
        private const val NSN_DIGITS = 10
        private const val INDIA = "+91"
    }
}
