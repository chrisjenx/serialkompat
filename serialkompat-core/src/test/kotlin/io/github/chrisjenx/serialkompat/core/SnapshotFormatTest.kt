package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The canonical text form of a [Snapshot] is the diffable, reviewable artifact.
 * It must be deterministic (byte-stable), invariant to input ordering, and
 * round-trip losslessly through [SnapshotFormat.parse].
 */
class SnapshotFormatTest {
    private fun representativeSnapshot(): Snapshot =
        Snapshot(
            contracts =
                listOf(
                    Contract(
                        serialName = "com.example.OrderEvent",
                        kind = ContractKind.CLASS,
                        elements =
                            listOf(
                                Element("id", "String"),
                                Element("amountCents", "Long"),
                                Element("note", "String", optional = true),
                                Element("status", "com.example.OrderStatus"),
                                Element("kind", "String", nullable = true, encodeDefault = EncodeDefaultMode.ALWAYS),
                                Element("tags", "List<String>", optional = true, jsonNames = listOf("labels")),
                            ),
                    ),
                    Contract(
                        serialName = "com.example.OrderStatus",
                        kind = ContractKind.ENUM,
                        enumValues = listOf("PAID", "CREATED", "CANCELLED"),
                    ),
                    Contract(
                        serialName = "com.example.Payment",
                        kind = ContractKind.SEALED,
                        discriminator = "type",
                        subtypes =
                            listOf(
                                Subtype("card", "com.example.CardPayment"),
                                Subtype("ach", "com.example.AchPayment"),
                            ),
                    ),
                ),
            config = SnapshotConfig(),
        )

    private val canonicalText =
        """
        @contract com.example.OrderEvent kind=CLASS
          amountCents: Long
          id: String
          kind: String nullable encodeDefault=ALWAYS
          note: String optional
          status: com.example.OrderStatus
          tags: List<String> optional jsonNames=[labels]

        @contract com.example.OrderStatus kind=ENUM
          values=[CANCELLED,CREATED,PAID]

        @contract com.example.Payment kind=SEALED discriminator=type
          subtypes:
            ach -> com.example.AchPayment
            card -> com.example.CardPayment

        @config
          classDiscriminator=type
          coerceInputValues=false
          encodeDefaults=false
          explicitNulls=true
          ignoreUnknownKeys=false
          namingStrategy=none
        """.trimIndent()

    @Test
    fun `serializes to the documented canonical form`() {
        assertEquals(canonicalText, SnapshotFormat.serialize(representativeSnapshot()))
    }

    @Test
    fun `parses the documented canonical form back to the model`() {
        assertEquals(representativeSnapshot(), SnapshotFormat.parse(canonicalText))
    }

    @Test
    fun `round-trips a representative snapshot`() {
        val s = representativeSnapshot()
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s)))
    }

    @Test
    fun `serialization is byte-stable across runs`() {
        val s = representativeSnapshot()
        assertEquals(SnapshotFormat.serialize(s), SnapshotFormat.serialize(s))
    }

    @Test
    fun `element ordering does not change the serialized text`() {
        val ordered =
            Contract(
                "T",
                ContractKind.CLASS,
                elements = listOf(Element("a", "String"), Element("b", "Int"), Element("c", "Long")),
            )
        val shuffled =
            Contract(
                "T",
                ContractKind.CLASS,
                elements = listOf(Element("c", "Long"), Element("a", "String"), Element("b", "Int")),
            )

        assertEquals(
            SnapshotFormat.serialize(Snapshot(listOf(ordered))),
            SnapshotFormat.serialize(Snapshot(listOf(shuffled))),
        )
    }

    @Test
    fun `contract ordering does not change the serialized text`() {
        val a = Contract("com.example.A", ContractKind.CLASS)
        val b = Contract("com.example.B", ContractKind.CLASS)

        assertEquals(
            SnapshotFormat.serialize(Snapshot(listOf(a, b))),
            SnapshotFormat.serialize(Snapshot(listOf(b, a))),
        )
    }

    @Test
    fun `parse tolerates blank lines and a trailing newline`() {
        val s = representativeSnapshot()
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s) + "\n\n"))
    }

    @Test
    fun `round-trips an empty snapshot`() {
        val s = Snapshot()
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s)))
    }
}
