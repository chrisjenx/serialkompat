package com.chrisjenx.serialkompat.core.format

/*
 * The codec's two escaping schemes, centralized so emit and parse share one
 * implementation per scheme. They are distinct and must stay separate:
 * token-escaping uses named escapes (`\s` = space) while list-escaping's
 * unescape keeps the char after `\` literally (`\s` would decode to `s`).
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
 * Escapes a value for inclusion in a `[a,b,c]` list literal: a `\` becomes
 * `\\` and the `,` separator becomes `\,`, so a value that legally contains a
 * comma (e.g. from `@SerialName`/`@JsonNames`) round-trips without being split.
 * Whitespace stays raw (see the tracked list-whitespace follow-up issue).
 */
internal fun escapeListValue(value: String): String = value.replace("\\", "\\\\").replace(",", "\\,")

/** Splits a list literal's inner text on unescaped `,`, reversing [escapeListValue]. */
internal fun splitEscaped(inner: String): List<String> {
    if (inner.isEmpty()) return emptyList()
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        when {
            c == '\\' && i + 1 < inner.length -> {
                current.append(inner[i + 1]) // consume the escape, keep the escaped char literally
                i += 2
            }
            c == ',' -> {
                tokens += current.toString()
                current.clear()
                i++
            }
            else -> {
                current.append(c)
                i++
            }
        }
    }
    tokens += current.toString()
    return tokens
}

/** Builds a `[a,b,c]` list literal with each value [escaped][escapeListValue]. */
internal fun listLiteral(values: List<String>): String =
    "[" + values.joinToString(",", transform = ::escapeListValue) + "]"

/** Parses a `[a,b,c]` list literal; an empty `[]` yields an empty list. */
internal fun parseListLiteral(literal: String): List<String> = splitEscaped(literal.trim().removeSurrounding("[", "]"))
