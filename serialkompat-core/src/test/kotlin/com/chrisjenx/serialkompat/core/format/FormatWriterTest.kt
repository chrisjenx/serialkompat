package com.chrisjenx.serialkompat.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [FormatWriter] is the ONLY home of layout (indentation, single-space token
 * joins, blank-line block joins) and of escaping application on emit.
 */
class FormatWriterTest {
    @Test
    fun `renders a contract block with every token kind at its canonical indent`() {
        val doc =
            FormatDoc(
                listOf(
                    Block(
                        listOf(
                            Line(
                                0,
                                listOf(
                                    Token.Word("@contract"),
                                    Token.Word("com.example.Payment"),
                                    Token.KeyValue("kind", "SEALED"),
                                    Token.KeyValue("discriminator", "type"),
                                ),
                            ),
                            Line(1, listOf(Token.Word("subtypes:"))),
                            Line(2, listOf(Token.ArrowPair("ach", "com.example.AchPayment"))),
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
                                    Token.FieldRef("tags", "List<String>"),
                                    Token.Word("optional"),
                                    Token.KeyList("jsonNames", listOf("labels")),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        val expected =
            """
            @contract com.example.Payment kind=SEALED discriminator=type
              subtypes:
                ach -> com.example.AchPayment

            @contract com.example.T kind=CLASS
              tags: List<String> optional jsonNames=[labels]
            """.trimIndent()
        assertEquals(expected, FormatWriter.render(doc))
    }

    @Test
    fun `applies token-escaping to every scalar value and list-escaping to list values`() {
        val doc =
            FormatDoc(
                listOf(
                    Block(
                        listOf(
                            Line(
                                0,
                                listOf(
                                    Token.Word("@contract"),
                                    Token.Word("order id class"),
                                    Token.KeyValue("kind", "ENUM"),
                                ),
                            ),
                            Line(1, listOf(Token.KeyList("values", listOf("A,B", "C")))),
                        ),
                    ),
                ),
            )
        val expected =
            """
            @contract order\sid\sclass kind=ENUM
              values=[A\,B,C]
            """.trimIndent()
        assertEquals(expected, FormatWriter.render(doc))
    }

    @Test
    fun `field ref renders its own colon-space as one token in the space join`() {
        val line = Line(1, listOf(Token.FieldRef("a: b field", "some weird type"), Token.Word("nullable")))
        val rendered = FormatWriter.render(FormatDoc(listOf(Block(listOf(line)))))
        assertEquals("""  a:\sb\sfield: some\sweird\stype nullable""", rendered)
    }

    @Test
    fun `renders an empty doc as an empty string`() {
        assertEquals("", FormatWriter.render(FormatDoc(emptyList())))
    }
}
