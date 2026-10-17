package com.sheen.spamblocker.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListParserTest {
    private val parser = ListParser()

    private fun success(r: ParseResult) = r as? ParseResult.Success ?: error("expected success, got $r")

    private val n1 = ParsedEntry("+919876543210", isPrefix = false)
    private val n2 = ParsedEntry("+918888812345", isPrefix = false)
    private val p1 = ParsedEntry("+9177777", isPrefix = true)

    @Test
    fun `txt with comments, blanks, formats and prefixes`() {
        val text = """
            # spam list
            +91 98765 43210

            08888812345   # inline comment
            77777*
        """.trimIndent()
        val r = success(parser.parse(text, ListFormat.TXT))
        assertEquals(listOf(n1, n2, p1), r.entries)
        assertEquals(0, r.failedLines)
    }

    @Test
    fun `csv with header picks the first column by default`() {
        val text = "number,label\n+919876543210,spam\n\"08888812345\",\"scam, loan\"\n"
        assertEquals(listOf(n1, n2), success(parser.parse(text, ListFormat.CSV)).entries)
    }

    @Test
    fun `csv column by name or number`() {
        val text = "label,phone\nspam,9876543210\nloan,8888812345\n"
        assertEquals(listOf(n1, n2), success(parser.parse(text, ListFormat.CSV, "phone")).entries)
        assertEquals(listOf(n1, n2), success(parser.parse(text, ListFormat.CSV, "2")).entries)
        assertTrue(parser.parse(text, ListFormat.CSV, "missing") is ParseResult.Rejected)
    }

    @Test
    fun `csv without header`() {
        assertEquals(listOf(n1, n2), success(parser.parse("9876543210\n8888812345", ListFormat.CSV)).entries)
    }

    @Test
    fun `json arrays of strings or objects`() {
        assertEquals(listOf(n1, p1), success(parser.parse("""["+919876543210", "+9177777*"]""", ListFormat.JSON)).entries)
        val objects = """[{"number": "+919876543210", "tag": "x"}, {"number": 8888812345}]"""
        assertEquals(listOf(n1, n2), success(parser.parse(objects, ListFormat.JSON)).entries)
        assertTrue(parser.parse("""{"number": "+919876543210"}""", ListFormat.JSON) is ParseResult.Rejected)
        assertTrue(parser.parse("not json", ListFormat.JSON) is ParseResult.Rejected)
    }

    @Test
    fun `malformed lines are counted and skipped`() {
        val r = success(parser.parse("9876543210\n8888812345\nhello\n", ListFormat.TXT))
        assertEquals(listOf(n1, n2), r.entries)
        assertEquals(1, r.failedLines)
        assertEquals(3, r.totalLines)
    }

    @Test
    fun `more than half failing rejects the whole list`() {
        assertTrue(parser.parse("9876543210\nfoo\nbar\n", ListFormat.TXT) is ParseResult.Rejected)
        // Exactly half is still accepted.
        assertTrue(parser.parse("9876543210\nfoo\n", ListFormat.TXT) is ParseResult.Success)
        assertTrue(parser.parse("""[{"x": 1}, {"y": 2}, "9876543210"]""", ListFormat.JSON) is ParseResult.Rejected)
    }

    @Test
    fun `empty list is rejected so the previous copy is kept`() {
        assertTrue(parser.parse("# nothing\n\n", ListFormat.TXT) is ParseResult.Rejected)
    }

    @Test
    fun `entry limit`() {
        val small = ListParser(maxEntries = 2)
        assertTrue(small.parse("9876543210\n8888812345\n7777712345", ListFormat.TXT) is ParseResult.Rejected)
        assertTrue(small.parse("9876543210\n8888812345", ListFormat.TXT) is ParseResult.Success)
    }

    @Test
    fun `duplicates are collapsed`() {
        assertEquals(listOf(n1), success(parser.parse("9876543210\n+919876543210", ListFormat.TXT)).entries)
    }
}
