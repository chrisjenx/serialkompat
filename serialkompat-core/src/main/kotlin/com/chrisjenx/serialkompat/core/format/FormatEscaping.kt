package com.chrisjenx.serialkompat.core.format

/*
 * The codec's escaping, centralized so emit and parse share one implementation.
 * There is a single escape alphabet — `\` → `\\`, space/tab/CR/LF → `\s\t\r\n`
 * (see escapeToken/unescapeToken) — reused by both schemes. List-escaping adds
 * exactly one thing on top: the `[a,b,c]` separator `,` is escaped to `\,`, so
 * a list value that legally contains a comma is not mistaken for a delimiter.
 */

/**
 * Escapes a name-bearing token (serial name, element name/type, discriminator,
 * subtype value/name, free-text config value) so it survives the codec's
 * positional delimiters. A `\` escapes itself and each whitespace delimiter —
 * space (word/`": "`/`" -> "` separators), tab, and CR/LF (line/block
 * separators). It is the **identity** for a token free of these characters.
 */
internal fun escapeToken(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace(" ", "\\s")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

/** Reverses [escapeToken] in a single pass so `\\` is not mistaken for an escape. */
internal fun unescapeToken(value: String): String {
    if ('\\' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (value[i + 1]) {
                '\\' -> out.append('\\')
                's' -> out.append(' ')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'n' -> out.append('\n')
                else -> out.append(value[i + 1]) // unknown escape: keep the char verbatim
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * Escapes a value for inclusion in a `[a,b,c]` list literal. Reuses the token
 * escape alphabet ([escapeToken] already doubles every `\` and named-escapes
 * every whitespace char) and then escapes the list separator `,` to `\,`.
 * Because every real backslash is already doubled, the introduced `\,` is
 * unambiguous. It is the identity on a value free of backslash, whitespace,
 * and comma, so whitespace-free lists serialize byte-identically (#146).
 */
internal fun escapeListValue(value: String): String = escapeToken(value).replace(",", "\\,")

/**
 * Splits a list literal's inner text on unescaped `,`, then reverses the
 * per-value escaping with [unescapeToken]. The split copies each `\`+next-char
 * escape pair through intact (so an escaped separator `\,` is never split on);
 * the pieces are then decoded — [unescapeToken] maps `\,` back to `,` via its
 * keep-the-char branch, alongside `\\`, `\s`, `\t`, `\r`, `\n`.
 */
internal fun splitEscaped(inner: String): List<String> {
    if (inner.isEmpty()) return emptyList()
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        when {
            c == '\\' && i + 1 < inner.length -> {
                current.append(c).append(inner[i + 1]) // keep the escape pair intact
                i += 2
            }
            c == ',' -> {
                pieces += current.toString()
                current.clear()
                i++
            }
            else -> {
                current.append(c)
                i++
            }
        }
    }
    pieces += current.toString()
    return pieces.map(::unescapeToken)
}

/** Builds a `[a,b,c]` list literal with each value [escaped][escapeListValue]. */
internal fun listLiteral(values: List<String>): String =
    "[" + values.joinToString(",", transform = ::escapeListValue) + "]"

/** Parses a `[a,b,c]` list literal; an empty `[]` yields an empty list. */
internal fun parseListLiteral(literal: String): List<String> = splitEscaped(literal.trim().removeSurrounding("[", "]"))
