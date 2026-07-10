package com.chrisjenx.serialkompat.core.format

import com.chrisjenx.serialkompat.core.ContractKind

/**
 * Parses canonical text into a [FormatDoc]. Kind-driven and positional: body
 * lines are classified by the contract's declared `kind`, and tokens by their
 * grammar position — never by shape (a serial name may legally look like
 * `kind=ENUM`; a config value may look like `[a,b]`).
 *
 * Tolerance contract (the #128 forward-compat channel): unknown header
 * tokens, unknown element flags, unknown `@config` keys, and a `@config` line
 * with no `=` are silently tolerated. Only malformed SHAPES reject — with the
 * codec's historical exception types (`IllegalStateException` for an unknown
 * block marker, `IllegalArgumentException` otherwise). New format facts must
 * arrive as new tokens/keys; a new body-line shape or kind is a format-version
 * event.
 *
 * The reader is indent-lenient: every line is trimmed before classification
 * and assigned its canonical indent level; input whitespace is never validated.
 */
internal object FormatReader {
    private val BLANK_LINE = Regex("\\n[ \\t]*\\n")
    private const val CONTRACT_PREFIX = "@contract "
    private const val CONFIG_HEADER = "@config"
    private const val SUBTYPE_ARROW = " -> "

    fun readDoc(text: String): FormatDoc {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return FormatDoc(emptyList())
        return FormatDoc(
            trimmed.split(BLANK_LINE).map { block ->
                val lines = block.lines()
                val header = lines.first().trim()
                when {
                    header.startsWith(CONTRACT_PREFIX) -> readContractBlock(header, lines.drop(1))
                    header == CONFIG_HEADER -> readConfigBlock(lines.drop(1))
                    else -> error("serialkompat: unexpected snapshot block starting with '$header'")
                }
            },
        )
    }

    private fun readContractBlock(
        header: String,
        bodyLines: List<String>,
    ): Block {
        val headerTokens = header.removePrefix(CONTRACT_PREFIX).trim().split(" ")
        val serialName = unescapeToken(headerTokens.first())
        // Position decides: token 0 is ALWAYS the name; the rest are key=value
        // (split on the FIRST '='), or bare words kept for the mapper to ignore.
        val rest =
            headerTokens.drop(1).map { token ->
                val eq = token.indexOf('=')
                if (eq >= 0) {
                    Token.KeyValue(token.substring(0, eq), unescapeToken(token.substring(eq + 1)))
                } else {
                    Token.Word(unescapeToken(token))
                }
            }
        // lastOrNull: today's parser overwrites on duplicate header keys (last
        // wins), and docToSnapshot's associate() is last-wins — keep them agreeing.
        val kindValue =
            rest.filterIsInstance<Token.KeyValue>().lastOrNull { it.key == "kind" }?.value
        requireNotNull(kindValue) { "serialkompat: contract '$serialName' is missing kind=" }
        val kind = ContractKind.valueOf(kindValue)

        val headerLine =
            Line(0, listOf(Token.Word("@contract"), Token.Word(serialName)) + rest)
        val body =
            bodyLines.map { it.trim() }.filter { it.isNotEmpty() }.map { readBodyLine(kind, serialName, it) }
        return Block(listOf(headerLine) + body)
    }

    private fun readBodyLine(
        kind: ContractKind,
        serialName: String,
        body: String,
    ): Line =
        when (kind) {
            ContractKind.CLASS, ContractKind.OBJECT -> readElementLine(body)
            ContractKind.ENUM -> {
                require(body.startsWith("values=[")) {
                    "serialkompat: malformed ENUM body line '$body' (expected 'values=[…]')"
                }
                // Whole-line: enum values legally contain spaces; never space-tokenize.
                Line(1, listOf(Token.KeyList("values", parseListLiteral(body.substringAfter("values=")))))
            }
            ContractKind.SEALED, ContractKind.POLYMORPHIC ->
                when {
                    body == "subtypes:" -> Line(1, listOf(Token.Word("subtypes:")))
                    SUBTYPE_ARROW in body -> {
                        val (value, name) = body.split(SUBTYPE_ARROW, limit = 2)
                        Line(
                            2,
                            listOf(
                                Token.ArrowPair(unescapeToken(value.trim()), unescapeToken(name.trim())),
                            ),
                        )
                    }
                    else -> throw IllegalArgumentException(
                        "serialkompat: malformed subtype line '$body' (expected 'value -> name')",
                    )
                }
            ContractKind.OPAQUE -> throw IllegalArgumentException(
                "serialkompat: OPAQUE contract '$serialName' must have no body lines, found '$body'",
            )
        }

    private fun readElementLine(body: String): Line {
        require(": " in body) {
            "serialkompat: malformed element line '$body' (expected 'name: type')"
        }
        val name = body.substringBefore(": ")
        val tokens = body.substringAfter(": ").trim().split(" ")
        // Position decides: the first token is ALWAYS the type, even if it
        // looks like a flag or key=value.
        val trailing =
            tokens.drop(1).map { token ->
                when {
                    token.startsWith("jsonNames=") ->
                        Token.KeyList("jsonNames", parseListLiteral(token.removePrefix("jsonNames=")))
                    else -> {
                        val eq = token.indexOf('=')
                        if (eq >= 0) {
                            Token.KeyValue(token.substring(0, eq), unescapeToken(token.substring(eq + 1)))
                        } else {
                            Token.Word(unescapeToken(token))
                        }
                    }
                }
            }
        return Line(
            1,
            listOf(Token.FieldRef(unescapeToken(name), unescapeToken(tokens.first()))) + trailing,
        )
    }

    private fun readConfigBlock(bodyLines: List<String>): Block {
        val entries =
            bodyLines
                .map { it.trim() }
                .filter { it.isNotEmpty() && '=' in it } // a line with no '=' is a tolerated no-op
                .map { line ->
                    val eq = line.indexOf('=')
                    Line(
                        1,
                        listOf(
                            Token.KeyValue(line.substring(0, eq), unescapeToken(line.substring(eq + 1))),
                        ),
                    )
                }
        return Block(listOf(Line(0, listOf(Token.Word(CONFIG_HEADER)))) + entries)
    }
}
