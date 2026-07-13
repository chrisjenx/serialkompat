package com.chrisjenx.serialkompat.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The codec's escaping is centralized here. Both schemes share one escape
 * alphabet (whitespace + backslash, see token-escaping); list-escaping adds
 * the separator escape `\,` on top so `[a,b,c]` values survive comma-splitting.
 */
class FormatEscapingTest {
    @Test
    fun `token-escaping round-trips whitespace newline and backslash`() {
        for (raw in listOf("plain", "a b", "tab\there", "line1\nline2", "cr\rhere", "back\\slash", "")) {
            assertEquals(raw, unescapeToken(escapeToken(raw)), "token round-trip failed for '$raw'")
        }
    }

    @Test
    fun `token-escaping is the identity on clean values`() {
        for (clean in listOf("CLASS", "ENUM", "true", "false", "POLYMORPHIC", "ALWAYS", "kotlin.String")) {
            assertEquals(clean, escapeToken(clean))
        }
    }

    @Test
    fun `list-escaping round-trips commas and backslashes through a literal`() {
        val values = listOf("A,B", "C", "back\\slash", "plain")
        assertEquals(values, parseListLiteral(listLiteral(values)))
    }

    @Test
    fun `list-escaping escapes whitespace into a single delimiter-safe token`() {
        // #146: list values share the token alphabet, so whitespace is escaped
        // and a `[...]` literal carries no raw space for tokenization to split on.
        assertEquals("values\\skept\\sraw", escapeListValue("values kept raw"))
        assertEquals("a\\tb", escapeListValue("a\tb"))
        assertEquals("l1\\nl2", escapeListValue("l1\nl2"))
    }

    @Test
    fun `parseListLiteral still reads legacy raw-space literals`() {
        // Backward-read compat: pre-#146 baselines emitted enum values= with raw
        // spaces (they round-tripped then); spaces are not escape sequences, so
        // they pass through unchanged on read.
        assertEquals(listOf("A B", "C"), parseListLiteral("[A B,C]"))
    }

    @Test
    fun `list-escaping round-trips whitespace newline tab comma and backslash`() {
        val values = listOf("a b", "tab\there", "l1\nl2", "cr\rhere", "with,comma", "back\\slash", "plain")
        assertEquals(values, parseListLiteral(listLiteral(values)))
    }

    @Test
    fun `empty list literal round-trips to an empty list`() {
        assertEquals("[]", listLiteral(emptyList()))
        assertEquals(emptyList(), parseListLiteral("[]"))
    }
}
