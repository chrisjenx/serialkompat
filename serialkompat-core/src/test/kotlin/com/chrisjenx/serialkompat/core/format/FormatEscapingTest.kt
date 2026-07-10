package com.chrisjenx.serialkompat.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two escaping schemes are distinct and centralized here: token-escaping
 * (whitespace + backslash, for name-bearing tokens) and list-escaping
 * (comma + backslash, for `[a,b,c]` literal values). Their unescape semantics
 * are incompatible, so they must never be merged.
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
    fun `list values keep raw spaces`() {
        // The list scheme deliberately does NOT escape whitespace (see the
        // tracked follow-up issue); an enum value with a space stays raw.
        assertEquals("values kept raw", escapeListValue("values kept raw"))
        assertEquals(listOf("A B", "C"), parseListLiteral("[A B,C]"))
    }

    @Test
    fun `empty list literal round-trips to an empty list`() {
        assertEquals("[]", listLiteral(emptyList()))
        assertEquals(emptyList(), parseListLiteral("[]"))
    }
}
