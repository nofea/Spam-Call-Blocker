package com.sheen.spamblocker.rules

import com.sheen.spamblocker.phone.NormalizedNumber
import com.sheen.spamblocker.phone.PhoneNormalizer

/**
 * A validated number pattern (SPEC §6.1). [digits] holds the E.164 digits
 * without the leading `+`, where `?` stands for exactly one digit. Suffix
 * patterns hold the trailing digits as typed.
 */
sealed interface CompiledPattern {
    val digits: String

    /** Matches numbers of exactly this length. */
    data class Exact(override val digits: String) : CompiledPattern

    /** Matches numbers starting with [digits]. */
    data class Prefix(override val digits: String) : CompiledPattern

    /** Matches numbers ending with [digits]. */
    data class Suffix(override val digits: String) : CompiledPattern

    val hasSingleDigitWildcard: Boolean get() = '?' in digits

    /** Canonical text form, e.g. `+9198765*`, `+91987654321?`, `*4321`. */
    val text: String
        get() = when (this) {
            is Exact -> "+$digits"
            is Prefix -> "+$digits*"
            is Suffix -> "*$digits"
        }

    /** Literal (non-`?`) digits; used to prefer the more specific of two matches. */
    val specificity: Int get() = digits.count { it != '?' }

    /** Linear match against E.164 digits (no `+`). [RuleIndex] uses tries instead. */
    fun matches(number: String): Boolean = when (this) {
        is Exact -> number.length == digits.length && digitsMatch(number, 0)
        is Prefix -> number.length >= digits.length && digitsMatch(number, 0)
        is Suffix -> number.length >= digits.length && digitsMatch(number, number.length - digits.length)
    }

    private fun digitsMatch(number: String, offset: Int): Boolean =
        digits.indices.all { i -> digits[i] == '?' || digits[i] == number[offset + i] }
}

class InvalidPatternException(message: String) : IllegalArgumentException(message)

/**
 * Parses user-written rules (SPEC §6.1): only `+`, digits, `?` and at most one
 * `*`, which must be at the end (prefix) or the start (suffix).
 *
 * Input without a leading `+` is treated as an Indian national number: exact
 * numbers go through [PhoneNormalizer]; patterns drop one leading `0` and get
 * `+91` prepended (`98765*` → `+9198765*`). Spaces and dashes are ignored.
 */
class WildcardCompiler(private val normalizer: PhoneNormalizer = PhoneNormalizer()) {

    fun compile(input: String): CompiledPattern {
        val s = input.filterNot { it.isWhitespace() || it == '-' }
        if (s.isEmpty()) invalid("Pattern is empty")
        s.firstOrNull { it !in ALLOWED_CHARS }?.let { invalid("'$it' isn't allowed; use digits, +, ? and *") }
        if (s.count { it == '*' } > 1) invalid("Only one * is allowed")
        if ('*' in s && s.first() != '*' && s.last() != '*') invalid("* must be at the start or the end")
        if (s.indexOf('+', startIndex = 1) >= 0) invalid("+ is only allowed at the start")
        if (s.none(Char::isDigit)) invalid("Pattern must contain at least one digit")

        if (s.first() == '*') {
            val body = s.drop(1)
            if ('+' in body) invalid("A suffix pattern can't contain +")
            if (body.length > MAX_DIGITS) invalid("Pattern is too long")
            return CompiledPattern.Suffix(body)
        }

        val isPrefix = s.last() == '*'
        val body = s.removeSuffix("*")
        val digits = when {
            body.startsWith('+') -> body.drop(1)
            !isPrefix && '?' !in body -> return exactFromNational(body)
            else -> "91" + body.removePrefix("0")
        }
        if (digits.isEmpty() || digits.none(Char::isDigit)) invalid("Pattern must contain at least one digit")
        if (digits.first() == '0') invalid("Country code can't start with 0")
        if (digits.length > MAX_DIGITS) invalid("Pattern is too long")
        return if (isPrefix) CompiledPattern.Prefix(digits) else CompiledPattern.Exact(digits)
    }

    fun compileOrNull(input: String): CompiledPattern? =
        try {
            compile(input)
        } catch (_: InvalidPatternException) {
            null
        }

    private fun exactFromNational(body: String): CompiledPattern.Exact =
        when (val n = normalizer.normalize(body)) {
            is NormalizedNumber.Parsed -> CompiledPattern.Exact(n.e164.drop(1))
            else -> invalid("Not a valid phone number")
        }

    private fun invalid(message: String): Nothing = throw InvalidPatternException(message)

    companion object {
        private const val ALLOWED_CHARS = "+0123456789?*"

        /** E.164 allows at most 15 digits. */
        const val MAX_DIGITS = 15

        /** Rules covering more numbers than this get a warning before saving (SPEC §6.1). */
        const val COVERAGE_WARNING_THRESHOLD = 10_000_000L

        /** Digits in an Indian E.164 number (`91` + 10), the assumed length for coverage. */
        private const val INDIAN_E164_DIGITS = 12

        /**
         * How many numbers [pattern] covers. Prefix and suffix patterns assume
         * Indian 12-digit numbers (15 digits for other country codes).
         */
        fun coverage(pattern: CompiledPattern): Long {
            val wildcards = pattern.digits.count { it == '?' }
            val free = when (pattern) {
                is CompiledPattern.Exact -> 0
                is CompiledPattern.Prefix -> {
                    val length = if (pattern.digits.startsWith("91")) INDIAN_E164_DIGITS else MAX_DIGITS
                    (length - pattern.digits.length).coerceAtLeast(0)
                }
                is CompiledPattern.Suffix -> (INDIAN_E164_DIGITS - pattern.digits.length).coerceAtLeast(0)
            }
            return pow10(wildcards + free)
        }

        private fun pow10(exp: Int): Long {
            var result = 1L
            repeat(exp.coerceAtMost(18)) { result *= 10 }
            return result
        }
    }
}
