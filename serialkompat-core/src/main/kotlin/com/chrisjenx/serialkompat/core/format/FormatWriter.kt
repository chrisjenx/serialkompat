package com.chrisjenx.serialkompat.core.format

/**
 * Renders a [FormatDoc] to canonical text. The ONLY home of layout —
 * indentation, single-space token joins, `\n\n` block joins — and of escaping
 * application on emit: token-escaping for every scalar value, list-escaping
 * for every [Token.KeyList] value. Mappers carry zero escaping knowledge.
 */
internal object FormatWriter {
    private const val INDENT = "  "

    fun render(doc: FormatDoc): String =
        doc.blocks.joinToString("\n\n") { block ->
            block.lines.joinToString("\n") { line ->
                INDENT.repeat(line.indent) + line.tokens.joinToString(" ", transform = ::renderToken)
            }
        }

    private fun renderToken(token: Token): String =
        when (token) {
            is Token.Word -> escapeToken(token.text)
            is Token.KeyValue -> token.key + "=" + escapeToken(token.value)
            is Token.KeyList -> token.key + "=" + listLiteral(token.values)
            is Token.FieldRef -> escapeToken(token.name) + ": " + escapeToken(token.type)
            is Token.ArrowPair -> escapeToken(token.left) + " -> " + escapeToken(token.right)
        }
}
