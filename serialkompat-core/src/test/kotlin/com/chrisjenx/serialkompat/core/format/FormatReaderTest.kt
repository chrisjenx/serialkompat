package com.chrisjenx.serialkompat.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [FormatReader] is kind-driven and positional: body lines are classified by
 * the contract's declared kind, tokens by grammar position (never by shape).
 * Unknown KEYS/FLAGS are tolerated (the #128 forward-compat channel); only
 * malformed SHAPES reject, with the codec's historical exception types.
 */
class FormatReaderTest {
    @Test
    fun `reads every line shape back to the canonical node tree`() {
        val text =
            """
            @contract com.example.P kind=SEALED discriminator=type polymorphicDefault=true
              subtypes:
                ach -> com.example.Ach

            @contract com.example.T kind=CLASS
              tags: List<String> optional jsonNames=[labels] encodeDefault=ALWAYS

            @config
              classDiscriminator=type
            """.trimIndent()
        val doc = FormatReader.readDoc(text)

        assertEquals(3, doc.blocks.size)
        val (p, t, config) = doc.blocks
        assertEquals(
            Line(
                0,
                listOf(
                    Token.Word("@contract"),
                    Token.Word("com.example.P"),
                    Token.KeyValue("kind", "SEALED"),
                    Token.KeyValue("discriminator", "type"),
                    Token.KeyValue("polymorphicDefault", "true"),
                ),
            ),
            p.lines[0],
        )
        assertEquals(Line(1, listOf(Token.Word("subtypes:"))), p.lines[1])
        assertEquals(Line(2, listOf(Token.ArrowPair("ach", "com.example.Ach"))), p.lines[2])
        assertEquals(
            Line(
                1,
                listOf(
                    Token.FieldRef("tags", "List<String>"),
                    Token.Word("optional"),
                    Token.KeyList("jsonNames", listOf("labels")),
                    Token.KeyValue("encodeDefault", "ALWAYS"),
                ),
            ),
            t.lines[1],
        )
        assertEquals(Line(1, listOf(Token.KeyValue("classDiscriminator", "type"))), config.lines[1])
    }

    @Test
    fun `enum values line is parsed whole-line, never space-tokenized`() {
        val doc =
            FormatReader.readDoc(
                """
                @contract E kind=ENUM
                  values=[A B,C]
                """.trimIndent(),
            )
        assertEquals(
            Line(1, listOf(Token.KeyList("values", listOf("A B", "C")))),
            doc.blocks.single().lines[1],
        )
    }

    @Test
    fun `header token zero is always the serial name even when it looks like a key-value`() {
        // "kind=ENUM" with no space is escape-identity, so it can appear as a
        // legal serial name; position, not shape, decides.
        val doc = FormatReader.readDoc("@contract kind=ENUM kind=CLASS")
        assertEquals(
            Line(
                0,
                listOf(Token.Word("@contract"), Token.Word("kind=ENUM"), Token.KeyValue("kind", "CLASS")),
            ),
            doc.blocks
                .single()
                .lines
                .single(),
        )
    }

    @Test
    fun `config values are always scalar even when they look like a list literal`() {
        val doc =
            FormatReader.readDoc(
                """
                @config
                  classDiscriminator=[a,b]
                """.trimIndent(),
            )
        assertEquals(
            Line(1, listOf(Token.KeyValue("classDiscriminator", "[a,b]"))),
            doc.blocks.single().lines[1],
        )
    }

    @Test
    fun `key-values split on the first equals sign`() {
        val doc = FormatReader.readDoc("@contract T kind=CLASS discriminator=a=b")
        val header =
            doc.blocks
                .single()
                .lines
                .single()
        assertEquals(Token.KeyValue("discriminator", "a=b"), header.tokens[3])
    }

    @Test
    fun `tolerates unknown header tokens element flags and config keys`() {
        // The #128 forward-compat channel: new facts arrive as new tokens/keys
        // and MUST be silently ignored, never rejected.
        val doc =
            FormatReader.readDoc(
                """
                @contract T kind=CLASS futureFact=x bareToken
                  f: String futureFlag futureKey=v

                @config
                  futureKey=v
                  lineWithNoEquals
                """.trimIndent(),
            )
        assertEquals(2, doc.blocks.size) // parses without error; unknowns survive as tokens or are dropped
    }

    @Test
    fun `is indent-lenient and assigns canonical indent levels`() {
        val doc =
            FormatReader.readDoc(
                """
                @contract P kind=SEALED discriminator=type
                      subtypes:
                ach -> A
                """.trimIndent(),
            )
        assertEquals(Line(1, listOf(Token.Word("subtypes:"))), doc.blocks.single().lines[1])
        assertEquals(Line(2, listOf(Token.ArrowPair("ach", "A"))), doc.blocks.single().lines[2])
    }

    @Test
    fun `read of rendered doc is the identity`() {
        val doc =
            FormatDoc(
                listOf(
                    Block(
                        listOf(
                            Line(
                                0,
                                listOf(
                                    Token.Word("@contract"),
                                    Token.Word("com.example.E"),
                                    Token.KeyValue("kind", "ENUM"),
                                ),
                            ),
                            // Includes an ENUM KeyList value containing a space (the S1 pin).
                            Line(1, listOf(Token.KeyList("values", listOf("A B", "C,D")))),
                        ),
                    ),
                    Block(
                        listOf(
                            Line(
                                0,
                                listOf(
                                    Token.Word("@contract"),
                                    Token.Word("com.example.T"),
                                    Token.KeyValue("kind", "CLASS"),
                                ),
                            ),
                            Line(
                                1,
                                listOf(
                                    Token.FieldRef("a name", "weird type"),
                                    Token.Word("optional"),
                                    Token.Word("nullable"),
                                    Token.KeyList("jsonNames", listOf("x,y")),
                                    Token.KeyValue("encodeDefault", "NEVER"),
                                ),
                            ),
                        ),
                    ),
                    Block(
                        listOf(
                            Line(0, listOf(Token.Word("@config"))),
                            Line(1, listOf(Token.KeyValue("classDiscriminator", "type"))),
                        ),
                    ),
                ),
            )
        assertEquals(doc, FormatReader.readDoc(FormatWriter.render(doc)))
    }

    @Test
    fun `rejects an unknown block marker with the historical exception type`() {
        assertFailsWith<IllegalStateException> { FormatReader.readDoc("@unknown thing") }
    }

    @Test
    fun `rejects a contract header missing kind`() {
        assertFailsWith<IllegalArgumentException> { FormatReader.readDoc("@contract T discriminator=x") }
    }

    @Test
    fun `rejects an unknown kind value`() {
        assertFailsWith<IllegalArgumentException> { FormatReader.readDoc("@contract T kind=FUTURE_KIND") }
    }

    @Test
    fun `rejects kind-inconsistent body shapes`() {
        // values=/arrow under CLASS, a field line under ENUM, any body under OPAQUE.
        assertFailsWith<IllegalArgumentException> {
            FormatReader.readDoc("@contract T kind=CLASS\n  values=[x]")
        }
        assertFailsWith<IllegalArgumentException> {
            FormatReader.readDoc("@contract T kind=CLASS\n  a -> b")
        }
        assertFailsWith<IllegalArgumentException> {
            FormatReader.readDoc("@contract E kind=ENUM\n  f: String")
        }
        assertFailsWith<IllegalArgumentException> {
            FormatReader.readDoc("@contract O kind=OPAQUE\n  anything")
        }
    }

    @Test
    fun `rejects a class element line with no colon separator`() {
        assertFailsWith<IllegalArgumentException> {
            FormatReader.readDoc("@contract T kind=CLASS\n  this_line_has_no_colon")
        }
    }

    @Test
    fun `tolerates CRLF line endings`() {
        val lf =
            "@contract com.example.T kind=CLASS\n  id: String\n\n@config\n  classDiscriminator=type"
        val crlf = lf.replace("\n", "\r\n")
        assertEquals(FormatReader.readDoc(lf), FormatReader.readDoc(crlf))
    }
}
