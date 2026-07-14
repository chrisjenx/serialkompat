package com.chrisjenx.serialkompat.core

/**
 * Shared JSON string escaping for the hand-rolled JSON-shaped reporters
 * ([JsonReporter], [SarifReporter]), kept in one place so the two emitters
 * cannot diverge on control-character handling. Pure — no I/O, no
 * kotlinx-serialization runtime.
 */
internal object JsonStrings {
    /** Renders [value] as a quoted JSON string literal, including the surrounding quotes. */
    fun quote(value: String): String =
        buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f") // form feed (Kotlin has no '\f' escape)
                    // JSON requires every control char < U+0020 to be escaped; those with short
                    // forms are handled above, the rest fall back to \u00XX.
                    else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
                }
            }
            append('"')
        }
}
