package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wire format is part of the snapshot (CBOR support). A CBOR snapshot carries the CBOR
 * configuration, round-trips through the text format, and is never compared contract-by-contract
 * against a snapshot of a different format.
 */
class CborFormatModelTest {
    private fun findings(
        change: Change,
        config: SnapshotConfig = SnapshotConfig.cbor(),
    ): List<Finding> = Classifier().classify(listOf(change), config, config)

    private fun List<Finding>.severity(direction: CompatibilityDirection): Severity? =
        singleOrNull { it.direction == direction }?.severity

    // ---- Model ---------------------------------------------------------------------------

    @Test
    fun `a default config is JSON`() {
        assertEquals(WireFormat.JSON, SnapshotConfig().format)
    }

    @Test
    fun `a CBOR config takes the JSON-equivalent semantics CBOR actually has`() {
        // Proved in CborSemanticsTest: a missing nullable field fails (explicitNulls=true), there is
        // no coercion, and polymorphism is array-style.
        val cbor = SnapshotConfig.cbor()
        assertEquals(WireFormat.CBOR, cbor.format)
        assertTrue(cbor.explicitNulls)
        assertFalse(cbor.coerceInputValues)
        assertTrue(cbor.useArrayPolymorphism)
        assertFalse(cbor.useAlternativeNames)
        assertFalse(cbor.ignoreUnknownKeys)
        assertFalse(cbor.encodeDefaults)
    }

    // ---- Text format ---------------------------------------------------------------------

    private val cborSnapshot =
        Snapshot(
            contracts = listOf(Contract("Item", ContractKind.CLASS, listOf(Element("id", "kotlin.String")))),
            config =
                SnapshotConfig.cbor(
                    ignoreUnknownKeys = true,
                    alwaysUseByteString = true,
                    useDefiniteLengthEncoding = true,
                    verifyValueTags = true,
                ),
        )

    @Test
    fun `a CBOR config writes format=cbor and only the keys CBOR has`() {
        val text = SnapshotFormat.serialize(cborSnapshot)
        val config = text.substringAfter("@config")
        assertEquals(
            """
            |
            |  alwaysUseByteString=true
            |  encodeDefaults=false
            |  encodeKeyTags=false
            |  encodeObjectTags=false
            |  encodeValueTags=false
            |  format=cbor
            |  ignoreUnknownKeys=true
            |  preferCborLabelsOverNames=false
            |  useDefiniteLengthEncoding=true
            |  verifyKeyTags=false
            |  verifyObjectTags=false
            |  verifyValueTags=true
            """.trimMargin(),
            config.trimEnd(),
        )
    }

    @Test
    fun `a CBOR snapshot round-trips`() {
        assertEquals(cborSnapshot, SnapshotFormat.parse(SnapshotFormat.serialize(cborSnapshot)))
    }

    @Test
    fun `a JSON config writes no format key, so existing snapshots are unchanged`() {
        val text = SnapshotFormat.serialize(Snapshot(emptyList(), SnapshotConfig()))
        assertFalse("format=" in text, text)
        assertFalse("verifyValueTags" in text, text)
    }

    @Test
    fun `format=json parses as JSON`() {
        val parsed = SnapshotFormat.parse("@config\n  format=json\n  ignoreUnknownKeys=true\n")
        assertEquals(SnapshotConfig(ignoreUnknownKeys = true), parsed.config)
    }

    @Test
    fun `an unknown format fails closed rather than reading as JSON`() {
        assertFailsWith<IllegalArgumentException> {
            SnapshotFormat.parse("@config\n  format=protobuf\n")
        }
    }

    // ---- Differ --------------------------------------------------------------------------

    @Test
    fun `a format change is one config change and no contract is compared`() {
        val json = Snapshot(listOf(Contract("Item", ContractKind.CLASS, listOf(Element("id", "kotlin.String")))))
        val cbor =
            Snapshot(
                listOf(Contract("Item", ContractKind.CLASS, listOf(Element("id", "kotlin.Int")))),
                SnapshotConfig.cbor(),
            )
        assertEquals(listOf<Change>(Change.ConfigChanged("format", "json", "cbor")), SnapshotDiffer.diff(json, cbor))
    }

    @Test
    fun `CBOR flags are diffed`() {
        val changes =
            SnapshotDiffer.diff(
                Snapshot(emptyList(), SnapshotConfig.cbor()),
                Snapshot(emptyList(), SnapshotConfig.cbor(alwaysUseByteString = true)),
            )
        assertEquals(listOf<Change>(Change.ConfigChanged("alwaysUseByteString", "false", "true")), changes)
    }

    // ---- Classifier ----------------------------------------------------------------------

    @Test
    fun `a format change is a WARN both ways naming both formats`() {
        val f =
            Classifier().classify(
                listOf(Change.ConfigChanged("format", "json", "cbor")),
                SnapshotConfig(),
                SnapshotConfig.cbor(),
            )
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.FORWARD))
        assertTrue(f.all { it.rule == Rules.CONFIG_FORMAT })
        assertEquals("wire format changed json -> cbor; contracts were not compared", f.first().message)
    }

    @Test
    fun `toggling alwaysUseByteString breaks both ways`() {
        val f = findings(Change.ConfigChanged("alwaysUseByteString", "false", "true"))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
        assertTrue(f.all { it.rule == Rules.CONFIG_CBOR_BYTE_STRING })
    }

    @Test
    fun `turning on definite-length encoding warns forward only`() {
        // Readers accept both lengths, but definite length writes an all-default object as 0xA0,
        // which a reader treats as null in a nullable slot.
        val on = findings(Change.ConfigChanged("useDefiniteLengthEncoding", "false", "true"))
        assertEquals(null, on.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.WARN, on.severity(CompatibilityDirection.FORWARD))
        assertTrue(on.all { it.rule == Rules.CONFIG_CBOR_DEFINITE_LENGTH })
        assertTrue(findings(Change.ConfigChanged("useDefiniteLengthEncoding", "true", "false")).isEmpty())
    }

    @Test
    fun `toggling preferCborLabelsOverNames is safe, since readers accept a name or a label`() {
        assertTrue(findings(Change.ConfigChanged("preferCborLabelsOverNames", "false", "true")).isEmpty())
        assertTrue(findings(Change.ConfigChanged("preferCborLabelsOverNames", "true", "false")).isEmpty())
    }

    @Test
    fun `changing a tag flag warns both ways`() {
        for (flag in CBOR_TAG_FLAGS) {
            val f = findings(Change.ConfigChanged(flag, "false", "true"))
            assertEquals(Severity.WARN, f.severity(CompatibilityDirection.BACKWARD), flag)
            assertEquals(Severity.WARN, f.severity(CompatibilityDirection.FORWARD), flag)
            assertTrue(f.all { it.rule == Rules.CONFIG_CBOR_TAGS }, flag)
        }
    }

    @Test
    fun `a CBOR config finding names Cbor, not Json`() {
        val f = findings(Change.ConfigChanged("ignoreUnknownKeys", "true", "false"))
        assertEquals("Cbor config", f.single().contract)
        assertEquals("Cbor ignoreUnknownKeys changed true -> false", f.single().message)
    }

    @Test
    fun `a JSON config finding still names Json`() {
        val f = findings(Change.ConfigChanged("ignoreUnknownKeys", "true", "false"), SnapshotConfig())
        assertEquals("Json config", f.single().contract)
    }

    // ---- Discriminator collisions under array polymorphism -------------------------------

    @Test
    fun `array polymorphism has no discriminator key, so nothing collides`() {
        // CBOR is always array-style, and so is Json { useArrayPolymorphism = true }.
        val base =
            Contract(
                "Shape",
                ContractKind.SEALED,
                discriminator = "type",
                subtypes = listOf(Subtype("circle", "circle")),
            )
        val sub = Contract("circle", ContractKind.CLASS, listOf(Element("type", "kotlin.String")), base = "Shape")
        for (config in listOf(SnapshotConfig(useArrayPolymorphism = true), SnapshotConfig.cbor())) {
            val snapshot = Snapshot(listOf(base, sub), config)
            assertTrue(
                SnapshotDiffer.diff(snapshot, snapshot).none { it is Change.DiscriminatorCollision },
                config.toString(),
            )
        }
    }

    private companion object {
        val CBOR_TAG_FLAGS =
            listOf(
                "encodeKeyTags",
                "encodeValueTags",
                "encodeObjectTags",
                "verifyKeyTags",
                "verifyValueTags",
                "verifyObjectTags",
            )
    }
}
