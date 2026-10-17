package com.sheen.spamblocker.lists

import com.sheen.spamblocker.phone.NormalizedNumber
import com.sheen.spamblocker.phone.PhoneNormalizer
import com.sheen.spamblocker.rules.CompiledPattern
import com.sheen.spamblocker.rules.WildcardCompiler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ListFormat { TXT, CSV, JSON }

/** One list entry: an E.164 number, or a prefix (E.164 without `*`) when [isPrefix]. */
data class ParsedEntry(val value: String, val isPrefix: Boolean)

sealed interface ParseResult {
    data class Success(val entries: List<ParsedEntry>, val failedLines: Int, val totalLines: Int) : ParseResult

    /** The whole list was rejected; the previous copy must be kept (SPEC §8.2). */
    data class Rejected(val reason: String) : ParseResult
}

/**
 * Parses downloaded spam lists (SPEC §8.2). An entry ending in `*` is a
 * prefix; anything else is an exact number normalized per SPEC §4. Lines that
 * fail are counted and skipped, but if more than half fail, the whole list is
 * rejected.
 */
class ListParser(
    private val normalizer: PhoneNormalizer = PhoneNormalizer(),
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val compiler = WildcardCompiler(normalizer)
    private val json = Json { isLenient = true }

    /**
     * @param csvColumn for CSV: a header name, or a 1-based column number. Null means the first column.
     */
    fun parse(text: String, format: ListFormat, csvColumn: String? = null): ParseResult {
        val raw = when (format) {
            ListFormat.TXT -> txtValues(text)
            ListFormat.CSV -> csvValues(text, csvColumn) ?: return ParseResult.Rejected("CSV column '$csvColumn' not found")
            ListFormat.JSON -> jsonValues(text) ?: return ParseResult.Rejected("Not a JSON array of numbers")
        }
        if (raw.isEmpty()) return ParseResult.Rejected("List is empty")

        val entries = LinkedHashSet<ParsedEntry>()
        var failed = 0
        for (value in raw) {
            val entry = value?.let(::parseEntry)
            if (entry == null) failed++ else entries += entry
        }
        if (failed * 2 > raw.size) {
            return ParseResult.Rejected("$failed of ${raw.size} entries couldn't be parsed")
        }
        if (entries.size > maxEntries) {
            return ParseResult.Rejected("List has ${entries.size} entries; the limit is $maxEntries")
        }
        return ParseResult.Success(entries.toList(), failed, raw.size)
    }

    private fun parseEntry(value: String): ParsedEntry? {
        val v = value.trim()
        if (v.endsWith('*')) {
            val p = compiler.compileOrNull(v) as? CompiledPattern.Prefix ?: return null
            if (p.hasSingleDigitWildcard) return null
            return ParsedEntry("+${p.digits}", isPrefix = true)
        }
        return when (val n = normalizer.normalize(v)) {
            is NormalizedNumber.Parsed -> ParsedEntry(n.e164, isPrefix = false)
            else -> null
        }
    }

    private fun txtValues(text: String): List<String> =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** Returns null if a named column isn't in the header. */
    private fun csvValues(text: String, column: String?): List<String?>? {
        val rows = text.lineSequence().filter { it.isNotBlank() }.map(::splitCsvLine).toList()
        if (rows.isEmpty()) return emptyList()

        val spec = column?.trim()?.takeIf { it.isNotEmpty() }
        val index: Int
        val body: List<List<String>>
        if (spec != null && spec.all(Char::isDigit)) {
            index = spec.toInt() - 1
            if (index < 0) return null
            body = dropHeader(rows, index)
        } else if (spec != null) {
            index = rows.first().indexOfFirst { it.trim().equals(spec, ignoreCase = true) }
            if (index < 0) return null
            body = rows.drop(1)
        } else {
            index = 0
            body = dropHeader(rows, index)
        }
        return body.map { it.getOrNull(index) }
    }

    /** A first row whose number cell has no digits is a header (SPEC §8.2: header optional). */
    private fun dropHeader(rows: List<List<String>>, index: Int): List<List<String>> =
        if (rows.first().getOrNull(index)?.any(Char::isDigit) == true) rows else rows.drop(1)

    /** RFC 4180 fields on one line: commas, double-quoted fields, `""` escapes. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { fields += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    /** `["+9198…", …]` or `[{"number": "+9198…", …}, …]`; null if it isn't a JSON array. */
    private fun jsonValues(text: String): List<String?>? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        return root.map { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> (element["number"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }
    }

    companion object {
        /** SPEC §8.2 hard limits. */
        const val MAX_ENTRIES = 500_000
        const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024
    }
}
