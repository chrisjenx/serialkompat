package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CBOR-only wire facts (design §6a): `@CborLabel`, `@ByteString`, tags and `@CborArray`. Each rule's
 * verdict is proved against real Cbor in CborRoundTripOracleTest; these pin the model, the text
 * format, the differ and the classifier.
 */
class CborRulesTest {
    private val cbor = SnapshotConfig.cbor()
    private val labels = SnapshotConfig.cbor(preferCborLabelsOverNames = true)

    private fun cls(
        vararg elements: Element,
        cborArray: List<String>? = null,
        objectTags: List<ULong> = emptyList(),
    ) = Contract("Entry", ContractKind.CLASS, elements.toList(), cborArray = cborArray, objectTags = objectTags)

    private fun findings(
        old: Contract,
        new: Contract,
        oldConfig: SnapshotConfig = cbor,
        newConfig: SnapshotConfig = cbor,
    ): List<Finding> {
        val changes = SnapshotDiffer.diff(Snapshot(listOf(old), oldConfig), Snapshot(listOf(new), newConfig))
        return Classifier().classify(changes, oldConfig, newConfig)
    }

    private fun List<Finding>.severity(direction: CompatibilityDirection): Severity =
        filter { it.direction == direction }.maxOfOrNull { it.severity } ?: Severity.SAFE

    private val backward = CompatibilityDirection.BACKWARD
    private val forward = CompatibilityDirection.FORWARD

    // ---- Fix hints -----------------------------------------------------------------------

    @Test
    fun `CBOR fix hints never suggest JSON-only remedies`() {
        val status = Contract("Status", ContractKind.ENUM, enumValues = listOf("A"))
        val statusAdded = Contract("Status", ContractKind.ENUM, enumValues = listOf("A", "B"))
        val hints =
            listOf(
                findings(
                    cls(Element("id", "kotlin.String")),
                    cls(Element("id", "kotlin.String"), Element("n", "kotlin.Int")),
                ),
                findings(
                    cls(Element("id", "kotlin.String"), Element("n", "kotlin.Int")),
                    cls(Element("id", "kotlin.String")),
                ),
                findings(status, statusAdded),
            ).flatten().mapNotNull { it.fixHint }
        assertTrue(hints.isNotEmpty())
        for (hint in hints) {
            for (jsonOnly in listOf("explicitNulls", "@JsonNames", "coerceInputValues")) {
                assertTrue(jsonOnly !in hint, "CBOR hint mentions $jsonOnly: $hint")
            }
        }
    }

    @Test
    fun `JSON fix hints are unchanged`() {
        val json = SnapshotConfig()
        val f =
            findings(
                cls(Element("id", "kotlin.String")),
                cls(Element("id", "kotlin.String"), Element("n", "kotlin.Int")),
                json,
                json,
            )
        assertTrue(f.any { "explicitNulls=false" in it.fixHint.orEmpty() })
    }

    // ---- Text format ---------------------------------------------------------------------

    @Test
    fun `CBOR element and contract facts round-trip through the text format`() {
        val snapshot =
            Snapshot(
                listOf(
                    cls(
                        Element("id", "kotlin.String", cborLabel = 1),
                        Element(
                            "data",
                            "kotlin.ByteArray",
                            byteString = true,
                            keyTags = listOf(3uL),
                            valueTags = listOf(4uL, 5uL),
                        ),
                        Element("n", "kotlin.Int", cborLabel = -2),
                        cborArray = listOf("id", "data", "n"),
                        objectTags = listOf(ULong.MAX_VALUE),
                    ),
                ),
                cbor,
            )
        val text = SnapshotFormat.serialize(snapshot)
        assertTrue("cborArray=[id,data,n]" in text, text)
        assertTrue("objectTags=[18446744073709551615]" in text, text)
        assertTrue("data: kotlin.ByteArray byteString keyTags=[3] valueTags=[4,5]" in text, text)
        assertTrue("id: kotlin.String cborLabel=1" in text, text)
        assertEquals(snapshot, SnapshotFormat.parse(text))
    }

    @Test
    fun `a contract without CBOR facts serializes as before`() {
        val text = SnapshotFormat.serialize(Snapshot(listOf(cls(Element("id", "kotlin.String")))))
        assertTrue("cbor" !in text.substringBefore("@config"), text)
        assertTrue("Tags" !in text, text)
    }

    @Test
    fun `tag order is significant`() {
        assertTrue(
            Element("v", "kotlin.Int", valueTags = listOf(1uL, 2uL)) !=
                Element("v", "kotlin.Int", valueTags = listOf(2uL, 1uL)),
        )
    }

    // ---- @CborLabel ----------------------------------------------------------------------

    @Test
    fun `a changed label breaks only the directions whose writer encodes labels`() {
        val old = cls(Element("id", "kotlin.String", cborLabel = 1))
        val new = cls(Element("id", "kotlin.String", cborLabel = 2))
        assertTrue(findings(old, new).isEmpty(), "no writer encodes labels")
        val f = findings(old, new, labels, labels)
        assertEquals(Severity.BREAK, f.severity(backward))
        assertEquals(Severity.BREAK, f.severity(forward))
        assertTrue(f.all { it.rule == Rules.PROPERTY_CBOR_LABEL })
    }

    @Test
    fun `adding a label breaks old readers once the new writer encodes it`() {
        val old = cls(Element("id", "kotlin.String"))
        val new = cls(Element("id", "kotlin.String", cborLabel = 1))
        val f = findings(old, new, labels, labels)
        assertEquals(Severity.SAFE, f.severity(backward), "the new reader still accepts the name")
        assertEquals(Severity.BREAK, f.severity(forward))
    }

    @Test
    fun `removing a label breaks new readers of label-encoded data`() {
        val old = cls(Element("id", "kotlin.String", cborLabel = 1))
        val new = cls(Element("id", "kotlin.String"))
        val f = findings(old, new, labels, labels)
        assertEquals(Severity.BREAK, f.severity(backward))
        assertEquals(Severity.SAFE, f.severity(forward))
    }

    @Test
    fun `a dropped label on an optional field is a silent default for a tolerant reader`() {
        val tolerant = SnapshotConfig.cbor(preferCborLabelsOverNames = true, ignoreUnknownKeys = true)
        val old = cls(Element("id", "kotlin.String", optional = true, cborLabel = 1))
        val new = cls(Element("id", "kotlin.String", optional = true, cborLabel = 2))
        val f = findings(old, new, tolerant, tolerant)
        assertEquals(Severity.WARN, f.severity(backward))
        assertEquals(Severity.WARN, f.severity(forward))
    }

    // ---- @ByteString ---------------------------------------------------------------------

    @Test
    fun `toggling ByteString breaks both ways`() {
        val f =
            findings(
                cls(Element("data", "kotlin.ByteArray")),
                cls(Element("data", "kotlin.ByteArray", byteString = true)),
            )
        assertEquals(Severity.BREAK, f.severity(backward))
        assertEquals(Severity.BREAK, f.severity(forward))
        assertTrue(f.all { it.rule == Rules.PROPERTY_BYTE_STRING })
    }

    @Test
    fun `toggling ByteString changes nothing under alwaysUseByteString`() {
        val always = SnapshotConfig.cbor(alwaysUseByteString = true)
        assertTrue(
            findings(
                cls(Element("data", "kotlin.ByteArray")),
                cls(Element("data", "kotlin.ByteArray", byteString = true)),
                always,
                always,
            ).isEmpty(),
        )
    }

    // ---- Tags ----------------------------------------------------------------------------

    @Test
    fun `changed value tags break where the writer encodes and the reader verifies`() {
        val old = cls(Element("v", "kotlin.Int", valueTags = listOf(1uL)))
        val new = cls(Element("v", "kotlin.Int", valueTags = listOf(2uL)))
        assertTrue(findings(old, new).isEmpty(), "tags are inert by default")
        val strictTags = SnapshotConfig.cbor(encodeValueTags = true, verifyValueTags = true)
        val f = findings(old, new, strictTags, strictTags)
        assertEquals(Severity.BREAK, f.severity(backward))
        assertEquals(Severity.BREAK, f.severity(forward))
        assertTrue(f.all { it.rule == Rules.CBOR_TAGS_CHANGED })
    }

    @Test
    fun `changed object tags break where the writer encodes and the reader verifies`() {
        val strictTags = SnapshotConfig.cbor(encodeObjectTags = true, verifyObjectTags = true)
        val f =
            findings(
                cls(Element("v", "kotlin.Int"), objectTags = listOf(1uL)),
                cls(Element("v", "kotlin.Int"), objectTags = listOf(2uL)),
                strictTags,
                strictTags,
            )
        assertEquals(Severity.BREAK, f.severity(backward))
        assertTrue(f.all { it.rule == Rules.CBOR_TAGS_CHANGED })
    }

    // ---- @CborArray ----------------------------------------------------------------------

    private val x = Element("x", "kotlin.Int")
    private val y = Element("y", "kotlin.Int")
    private val zOptional = Element("z", "kotlin.Int", optional = true, encodeDefault = EncodeDefaultMode.ABSENT)
    private val zRequired = Element("z", "kotlin.Int")

    @Test
    fun `appending an optional element is readable by new code but crashes old code, even tolerant`() {
        // Proved in CborSemanticsTest: an extra trailing element fails even with ignoreUnknownKeys.
        val tolerant = SnapshotConfig.cbor(ignoreUnknownKeys = true)
        val f =
            findings(
                cls(x, y, cborArray = listOf("x", "y")),
                cls(x, y, zOptional, cborArray = listOf("x", "y", "z")),
                tolerant,
                tolerant,
            )
        assertEquals(Severity.SAFE, f.severity(backward))
        assertEquals(Severity.BREAK, f.severity(forward))
        assertTrue(f.all { it.rule == Rules.CBOR_ARRAY_LAYOUT }, f.toString())
    }

    @Test
    fun `appending a required element breaks both ways`() {
        val f =
            findings(cls(x, y, cborArray = listOf("x", "y")), cls(x, y, zRequired, cborArray = listOf("x", "y", "z")))
        assertEquals(Severity.BREAK, f.severity(backward))
        assertEquals(Severity.BREAK, f.severity(forward))
    }

    @Test
    fun `dropping the trailing optional element is readable by old code only`() {
        val f =
            findings(cls(x, y, zOptional, cborArray = listOf("x", "y", "z")), cls(x, y, cborArray = listOf("x", "y")))
        assertEquals(Severity.BREAK, f.severity(backward))
        assertEquals(Severity.SAFE, f.severity(forward))
    }

    @Test
    fun `reordering elements breaks both ways`() {
        val f = findings(cls(x, y, cborArray = listOf("x", "y")), cls(x, y, cborArray = listOf("y", "x")))
        assertEquals(Severity.BREAK, f.severity(backward))
        assertEquals(Severity.BREAK, f.severity(forward))
        assertTrue(f.any { it.rule == Rules.CBOR_ARRAY_LAYOUT })
    }

    @Test
    fun `adding or removing CborArray breaks both ways`() {
        val f = findings(cls(x, y), cls(x, y, cborArray = listOf("x", "y")))
        assertEquals(Severity.BREAK, f.severity(backward))
        assertEquals(Severity.BREAK, f.severity(forward))
    }

    @Test
    fun `an unchanged CborArray layout reports nothing`() {
        val c = cls(x, y, cborArray = listOf("x", "y"))
        assertTrue(findings(c, c).isEmpty())
    }

    @Test
    fun `a CborArray element with an omitted default before a later element is flagged on every run`() {
        val mid = Element("m", "kotlin.Int", optional = true, encodeDefault = EncodeDefaultMode.ABSENT)
        val c = cls(x, mid, y, cborArray = listOf("x", "m", "y"))
        val f = findings(c, c)
        assertEquals(Severity.BREAK, f.severity(backward))
        assertTrue(f.all { it.rule == Rules.CBOR_ARRAY_OMITTED_DEFAULT }, f.toString())
        // Encoding defaults removes the gap; so does @EncodeDefault(ALWAYS).
        val encodes = SnapshotConfig.cbor(encodeDefaults = true)
        assertTrue(findings(c, c, encodes, encodes).isEmpty())
        val always =
            cls(
                x,
                Element("m", "kotlin.Int", optional = true, encodeDefault = EncodeDefaultMode.ALWAYS),
                y,
                cborArray = listOf("x", "m", "y"),
            )
        assertTrue(findings(always, always).isEmpty())
    }

    @Test
    fun `a CborArray element whose @EncodeDefault mode was not recorded is a WARN both ways`() {
        // A hidden ALWAYS would close the gap and a hidden NEVER would open it: unknown, so WARN, never SAFE.
        val mid = Element("m", "kotlin.Int", optional = true, encodeDefault = null)
        val c = cls(x, mid, y, cborArray = listOf("x", "m", "y"))
        val f = findings(c, c)
        assertEquals(Severity.WARN, f.severity(backward))
        assertEquals(Severity.WARN, f.severity(forward))
        assertTrue(f.all { it.rule == Rules.CBOR_ARRAY_OMITTED_DEFAULT }, f.toString())
    }

    @Test
    fun `a trailing optional element is not an omitted-default gap`() {
        val c = cls(x, y, zOptional, cborArray = listOf("x", "y", "z"))
        assertTrue(findings(c, c).isEmpty())
    }
}
