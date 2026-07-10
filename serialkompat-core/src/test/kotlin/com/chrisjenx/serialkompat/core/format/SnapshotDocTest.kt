package com.chrisjenx.serialkompat.core.format

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.EncodeDefaultMode
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.Subtype
import kotlin.test.Test
import kotlin.test.assertEquals

/** The snapshot↔doc mappers own ALL domain rules: kind→body shape, config key order, element token order. */
class SnapshotDocTest {
    @Test
    fun `maps every contract kind to its canonical block shape`() {
        val snapshot =
            Snapshot(
                listOf(
                    Contract(
                        "com.example.T",
                        ContractKind.CLASS,
                        elements =
                            listOf(
                                Element(
                                    "kind",
                                    "String",
                                    nullable = true,
                                    encodeDefault = EncodeDefaultMode.ALWAYS,
                                ),
                                Element("tags", "List<String>", optional = true, jsonNames = listOf("labels")),
                            ),
                    ),
                    Contract("com.example.E", ContractKind.ENUM, enumValues = listOf("A", "B")),
                    Contract(
                        "com.example.P",
                        ContractKind.SEALED,
                        discriminator = "type",
                        subtypes = listOf(Subtype("ach", "com.example.Ach")),
                        hasPolymorphicDefault = true,
                    ),
                    Contract("com.example.O", ContractKind.OPAQUE),
                ),
                SnapshotConfig(),
            )
        val doc = snapshotToDoc(snapshot)

        // Contracts are model-sorted: E, O, P, T — then the @config block.
        assertEquals(5, doc.blocks.size)
        val (e, o, p, t, config) = doc.blocks

        assertEquals(
            Line(
                0,
                listOf(Token.Word("@contract"), Token.Word("com.example.E"), Token.KeyValue("kind", "ENUM")),
            ),
            e.lines.first(),
        )
        assertEquals(Line(1, listOf(Token.KeyList("values", listOf("A", "B")))), e.lines[1])

        assertEquals(1, o.lines.size) // OPAQUE: header only, no body

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
            p.lines.first(),
        )
        assertEquals(Line(1, listOf(Token.Word("subtypes:"))), p.lines[1])
        assertEquals(Line(2, listOf(Token.ArrowPair("ach", "com.example.Ach"))), p.lines[2])

        assertEquals(
            Line(
                1,
                listOf(
                    Token.FieldRef("kind", "String"),
                    Token.Word("nullable"),
                    Token.KeyValue("encodeDefault", "ALWAYS"),
                ),
            ),
            t.lines[1], // elements are model-sorted by name: kind, tags
        )
        assertEquals(
            Line(
                1,
                listOf(
                    Token.FieldRef("tags", "List<String>"),
                    Token.Word("optional"),
                    Token.KeyList("jsonNames", listOf("labels")),
                ),
            ),
            t.lines[2],
        )

        assertEquals(Line(0, listOf(Token.Word("@config"))), config.lines.first())
        assertEquals(
            listOf(
                "classDiscriminator",
                "classDiscriminatorMode",
                "coerceInputValues",
                "encodeDefaults",
                "explicitNulls",
                "ignoreUnknownKeys",
                "namingStrategy",
                "useAlternativeNames",
            ),
            config.lines.drop(1).map { (it.tokens.single() as Token.KeyValue).key },
        )
    }
}
