package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
          classDiscriminatorMode=POLYMORPHIC
          coerceInputValues=false
          encodeDefaults=false
          explicitNulls=true
          ignoreUnknownKeys=false
          namingStrategy=none
          useAlternativeNames=true
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

    @Test
    fun `round-trips non-default classDiscriminatorMode and useAlternativeNames`() {
        val s =
            Snapshot(
                config = SnapshotConfig(classDiscriminatorMode = "NONE", useAlternativeNames = false),
            )
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s)))
    }

    @Test
    fun `round-trips enum values and jsonNames containing the list separator`() {
        // A @SerialName / @JsonNames value may legally contain a comma; it must
        // not be split into two values on round-trip (silent contract corruption).
        val s =
            Snapshot(
                listOf(
                    Contract("E", ContractKind.ENUM, enumValues = listOf("A,B", "C")),
                    Contract(
                        "T",
                        ContractKind.CLASS,
                        elements = listOf(Element("f", "String", jsonNames = listOf("a,b", "c"))),
                    ),
                ),
            )
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s)))
    }

    @Test
    fun `rejects a malformed element line rather than fabricating an element`() {
        val text =
            """
            @contract T kind=CLASS
              this_line_has_no_colon
            """.trimIndent()
        assertFailsWith<IllegalArgumentException> { SnapshotFormat.parse(text) }
    }

    @Test
    fun `round-trips an OBJECT contract`() {
        val s = Snapshot(listOf(Contract("com.example.Ping", ContractKind.OBJECT)))
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s)))
    }

    @Test
    fun `round-trips a POLYMORPHIC contract with subtypes`() {
        val s =
            Snapshot(
                listOf(
                    Contract(
                        "com.example.Event",
                        ContractKind.POLYMORPHIC,
                        discriminator = "type",
                        subtypes = listOf(Subtype("a", "com.example.A"), Subtype("b", "com.example.B")),
                    ),
                ),
            )
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s)))
    }

    // --- adversarial name fields ------------------------------------------------
    // @SerialName / @JsonNames / @JsonClassDiscriminator may legally contain the
    // codec's own structural delimiters (space, ": ", " -> ", "="), newlines, or
    // backslashes. Every name-bearing field must round-trip losslessly, or the
    // snapshot silently corrupts and the gate under/over-reports changes.

    private fun assertRoundTrips(snapshot: Snapshot) {
        assertEquals(snapshot, SnapshotFormat.parse(SnapshotFormat.serialize(snapshot)))
    }

    @Test
    fun `round-trips a serial name containing spaces`() {
        assertRoundTrips(Snapshot(listOf(Contract("order id class", ContractKind.CLASS))))
    }

    @Test
    fun `round-trips an element name and type containing spaces and colons`() {
        assertRoundTrips(
            Snapshot(
                listOf(
                    Contract(
                        "T",
                        ContractKind.CLASS,
                        elements = listOf(Element("a: b field", "some weird type", optional = true, nullable = true)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `round-trips a serial name containing a newline`() {
        assertRoundTrips(Snapshot(listOf(Contract("line1\nline2", ContractKind.CLASS))))
    }

    @Test
    fun `round-trips a serial name that looks like another codec line`() {
        // A serial name that mimics a header or config line must not be misparsed.
        assertRoundTrips(
            Snapshot(
                listOf(
                    Contract("@config kind=ENUM", ContractKind.OPAQUE),
                    Contract("subtypes: values=[x]", ContractKind.CLASS),
                ),
            ),
        )
    }

    @Test
    fun `round-trips a custom discriminator and subtype values containing spaces`() {
        assertRoundTrips(
            Snapshot(
                listOf(
                    Contract(
                        "com.example.Event",
                        ContractKind.SEALED,
                        discriminator = "@ event type",
                        subtypes = listOf(Subtype("kind a", "com.example.A"), Subtype("kind b", "com.example.B")),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `round-trips names containing backslashes and unicode`() {
        assertRoundTrips(
            Snapshot(
                listOf(
                    Contract(
                        "com.example.Ünïcodé\\Name",
                        ContractKind.CLASS,
                        elements = listOf(Element("naïve\\field", "kotlin.String")),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `round-trips config free-text values containing whitespace and a newline`() {
        // classDiscriminator / namingStrategy are free text. A value with leading/trailing
        // whitespace or a newline was serialized raw, then lost on parse (per-line trim / line
        // split), so serialize→parse was not idempotent → a spurious or missed config diff.
        assertRoundTrips(
            Snapshot(
                contracts = emptyList(),
                config =
                    SnapshotConfig(
                        classDiscriminator = " leading and trailing ",
                        namingStrategy = "line one\nline two",
                    ),
            ),
        )
    }
}
