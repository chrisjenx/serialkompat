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

    private fun tagFindings(
        field: String,
        old: SnapshotConfig,
        new: SnapshotConfig,
    ): List<Finding> =
        Classifier().classify(
            listOf(Change.ConfigChanged(field, old.flag(field).toString(), new.flag(field).toString())),
            old,
            new,
        )

    private fun SnapshotConfig.flag(field: String): Boolean =
        SnapshotConfig::class.java
            .getMethod("get" + field.replaceFirstChar(Char::uppercase))
            .invoke(this) as Boolean

    @Test
    fun `a new reader that starts verifying tags breaks on old untagged data`() {
        // Proved in CborRoundTripOracleTest: a verifying reader rejects data written without the tags.
        for ((verify, encode) in TAG_GROUPS) {
            val f = tagFindings(verify, SnapshotConfig.cbor(), SnapshotConfig.cbor().with(verify))
            assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD), verify)
            assertEquals(null, f.severity(CompatibilityDirection.FORWARD), verify)
            assertTrue(f.all { it.rule == Rules.CONFIG_CBOR_TAGS }, verify)
            // Old data that already carried the tags is fine.
            val tagged = SnapshotConfig.cbor().with(encode)
            assertTrue(tagFindings(verify, tagged, tagged.with(verify)).isEmpty(), verify)
        }
    }

    @Test
    fun `a new writer that stops encoding tags breaks old verifying readers`() {
        for ((verify, encode) in TAG_GROUPS) {
            val old = SnapshotConfig.cbor().with(encode).with(verify)
            val new = SnapshotConfig.cbor().with(verify)
            val f = tagFindings(encode, old, new)
            assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD), encode)
        }
    }

    @Test
    fun `encoding tags for readers that skip them is safe`() {
        for ((_, encode) in TAG_GROUPS) {
            assertTrue(tagFindings(encode, SnapshotConfig.cbor(), SnapshotConfig.cbor().with(encode)).isEmpty(), encode)
        }
    }

    private fun SnapshotConfig.with(flag: String): SnapshotConfig =
        when (flag) {
            "encodeKeyTags" -> copy(encodeKeyTags = true)
            "encodeValueTags" -> copy(encodeValueTags = true)
            "encodeObjectTags" -> copy(encodeObjectTags = true)
            "verifyKeyTags" -> copy(verifyKeyTags = true)
            "verifyValueTags" -> copy(verifyValueTags = true)
            "verifyObjectTags" -> copy(verifyObjectTags = true)
            else -> error(flag)
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
        /** (verify flag, encode flag) per tag group. */
        val TAG_GROUPS =
            listOf(
                "verifyKeyTags" to "encodeKeyTags",
                "verifyValueTags" to "encodeValueTags",
                "verifyObjectTags" to "encodeObjectTags",
            )
    }
}
