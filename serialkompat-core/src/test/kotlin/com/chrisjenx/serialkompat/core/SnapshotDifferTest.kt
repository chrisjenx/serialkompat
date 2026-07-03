package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The differ produces a purely structural, direction-neutral [Change] list from
 * an old→new [Snapshot] pair. Identity is by serial name (contracts) and by key
 * (elements); severity and rule naming are the classifier's job, not the
 * differ's. Rename/move *detection* is deferred to #12 — here a key change
 * surfaces as remove + add.
 */
class SnapshotDifferTest {
    private fun clazz(
        serialName: String,
        vararg elements: Element,
    ) = Contract(serialName, ContractKind.CLASS, elements = elements.toList())

    private fun snapshot(
        vararg contracts: Contract,
        config: SnapshotConfig = SnapshotConfig(),
    ) = Snapshot(contracts.toList(), config)

    private fun diff(
        old: Snapshot,
        new: Snapshot,
    ) = SnapshotDiffer.diff(old, new)

    @Test
    fun `identical snapshots produce no changes`() {
        val s = snapshot(clazz("T", Element("id", "String"), Element("n", "Int")))
        assertEquals(emptyList(), diff(s, s))
    }

    @Test
    fun `reordered elements produce no changes`() {
        val old = snapshot(clazz("T", Element("a", "String"), Element("b", "Int")))
        val new = snapshot(clazz("T", Element("b", "Int"), Element("a", "String")))
        assertEquals(emptyList(), diff(old, new))
    }

    @Test
    fun `added contract`() {
        val old = snapshot()
        val new = snapshot(clazz("com.example.T"))
        assertEquals(listOf(Change.ContractAdded("com.example.T", ContractKind.CLASS)), diff(old, new))
    }

    @Test
    fun `removed contract`() {
        val old = snapshot(clazz("com.example.T"))
        val new = snapshot()
        assertEquals(listOf(Change.ContractRemoved("com.example.T", ContractKind.CLASS)), diff(old, new))
    }

    @Test
    fun `added element`() {
        val old = snapshot(clazz("T", Element("id", "String")))
        val new = snapshot(clazz("T", Element("id", "String"), Element("extra", "Int", optional = true)))
        assertEquals(listOf(Change.ElementAdded("T", Element("extra", "Int", optional = true))), diff(old, new))
    }

    @Test
    fun `removed element`() {
        val old = snapshot(clazz("T", Element("id", "String"), Element("gone", "Int")))
        val new = snapshot(clazz("T", Element("id", "String")))
        assertEquals(listOf(Change.ElementRemoved("T", Element("gone", "Int"))), diff(old, new))
    }

    @Test
    fun `element type changed`() {
        val old = snapshot(clazz("T", Element("id", "String")))
        val new = snapshot(clazz("T", Element("id", "Long")))
        assertEquals(listOf(Change.ElementTypeChanged("T", "id", "String", "Long")), diff(old, new))
    }

    @Test
    fun `element optionality changed`() {
        val old = snapshot(clazz("T", Element("id", "String", optional = false)))
        val new = snapshot(clazz("T", Element("id", "String", optional = true)))
        assertEquals(
            listOf(Change.ElementOptionalityChanged("T", "id", wasOptional = false, nowOptional = true)),
            diff(old, new),
        )
    }

    @Test
    fun `element nullability changed`() {
        val old = snapshot(clazz("T", Element("id", "String", nullable = false)))
        val new = snapshot(clazz("T", Element("id", "String", nullable = true)))
        assertEquals(
            listOf(Change.ElementNullabilityChanged("T", "id", wasNullable = false, nowNullable = true)),
            diff(old, new),
        )
    }

    @Test
    fun `element jsonNames changed`() {
        val old = snapshot(clazz("T", Element("id", "String", jsonNames = listOf("ident"))))
        val new = snapshot(clazz("T", Element("id", "String", jsonNames = listOf("ident", "identifier"))))
        assertEquals(
            listOf(Change.ElementJsonNamesChanged("T", "id", listOf("ident"), listOf("ident", "identifier"))),
            diff(old, new),
        )
    }

    @Test
    fun `reordered contracts produce no changes`() {
        val a = clazz("com.example.A", Element("x", "String"))
        val b = clazz("com.example.B", Element("y", "Int"))
        assertEquals(emptyList(), diff(snapshot(a, b), snapshot(b, a)))
    }

    @Test
    fun `enum value added`() {
        val old = snapshot(Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B")))
        val new = snapshot(Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B", "C")))
        assertEquals(listOf(Change.EnumValueAdded("E", "C")), diff(old, new))
    }

    @Test
    fun `enum value added records whether the baseline fields can coerce a fallback (#129)`() {
        fun flagWhenReferencedBy(vararg refs: Element): Boolean {
            val old = snapshot(Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B")), clazz("H", *refs))
            val new = snapshot(Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B", "C")), clazz("H", *refs))
            return diff(old, new).filterIsInstance<Change.EnumValueAdded>().single().baselineFieldsCoercible
        }

        // A defaulted (optional) direct field can coerce an unknown value to its default.
        assertTrue(flagWhenReferencedBy(Element("e", "E", optional = true)))
        // A nullable-but-still-defaulted direct field is coercible; nullable != required.
        assertTrue(flagWhenReferencedBy(Element("e", "E", optional = true, nullable = true)))
        // A required direct field has no default to coerce to.
        assertFalse(flagWhenReferencedBy(Element("e", "E", optional = false)))
        // A nested (List) usage is not coerced element-wise.
        assertFalse(flagWhenReferencedBy(Element("es", "List<E>", optional = true)))
        // A required reference disqualifies the enum even alongside an optional one (worst case wins).
        assertFalse(flagWhenReferencedBy(Element("e", "E", optional = true), Element("e2", "E", optional = false)))
        // A field whose type merely contains the enum's name as a substring is not a reference.
        assertFalse(flagWhenReferencedBy(Element("x", "Enclosing", optional = true)))

        // A bare enum with no referencing field (only a top-level decode) is not coercible.
        val oldBare = snapshot(Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B")))
        val newBare = snapshot(Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B", "C")))
        assertFalse(diff(oldBare, newBare).filterIsInstance<Change.EnumValueAdded>().single().baselineFieldsCoercible)
    }

    @Test
    fun `enum value removed`() {
        val old = snapshot(Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B", "C")))
        val new = snapshot(Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B")))
        assertEquals(listOf(Change.EnumValueRemoved("E", "C")), diff(old, new))
    }

    @Test
    fun `subtype added`() {
        val old =
            snapshot(Contract("P", ContractKind.SEALED, discriminator = "type", subtypes = listOf(Subtype("a", "A"))))
        val new =
            snapshot(
                Contract(
                    "P",
                    ContractKind.SEALED,
                    discriminator = "type",
                    subtypes = listOf(Subtype("a", "A"), Subtype("b", "B")),
                ),
            )
        assertEquals(listOf(Change.SubtypeAdded("P", Subtype("b", "B"))), diff(old, new))
    }

    @Test
    fun `subtype removed`() {
        val old =
            snapshot(
                Contract(
                    "P",
                    ContractKind.SEALED,
                    discriminator = "type",
                    subtypes = listOf(Subtype("a", "A"), Subtype("b", "B")),
                ),
            )
        val new =
            snapshot(Contract("P", ContractKind.SEALED, discriminator = "type", subtypes = listOf(Subtype("a", "A"))))
        assertEquals(listOf(Change.SubtypeRemoved("P", Subtype("b", "B"))), diff(old, new))
    }

    @Test
    fun `discriminator changed`() {
        val old = snapshot(Contract("P", ContractKind.SEALED, discriminator = "type"))
        val new = snapshot(Contract("P", ContractKind.SEALED, discriminator = "kind"))
        assertEquals(listOf(Change.DiscriminatorChanged("P", "type", "kind")), diff(old, new))
    }

    @Test
    fun `config field changed`() {
        val old = snapshot(config = SnapshotConfig(ignoreUnknownKeys = false))
        val new = snapshot(config = SnapshotConfig(ignoreUnknownKeys = true))
        assertEquals(listOf(Change.ConfigChanged("ignoreUnknownKeys", "false", "true")), diff(old, new))
    }

    @Test
    fun `changing a contract's kind surfaces as remove and add`() {
        val old = snapshot(clazz("T", Element("id", "String")))
        val new = snapshot(Contract("T", ContractKind.ENUM, enumValues = listOf("A")))
        val changes = diff(old, new)
        assertTrue(Change.ContractRemoved("T", ContractKind.CLASS) in changes)
        assertTrue(Change.ContractAdded("T", ContractKind.ENUM) in changes)
    }

    @Test
    fun `output is deterministic`() {
        val old = snapshot(clazz("com.example.A", Element("x", "String")), clazz("com.example.B"))
        val new =
            snapshot(
                clazz("com.example.A", Element("x", "Long")),
                clazz("com.example.C"),
            )
        assertEquals(diff(old, new), diff(old, new))
    }

    @Test
    fun `multiple simultaneous mutations on one element each surface as their own change`() {
        val old = snapshot(clazz("T", Element("id", "kotlin.String", optional = false, nullable = false)))
        val new = snapshot(clazz("T", Element("id", "kotlin.Long", optional = true, nullable = true)))
        val changes = diff(old, new)
        assertTrue(Change.ElementTypeChanged("T", "id", "kotlin.String", "kotlin.Long") in changes)
        assertTrue(
            Change.ElementOptionalityChanged("T", "id", wasOptional = false, nowOptional = true) in changes,
        )
        assertTrue(
            Change.ElementNullabilityChanged("T", "id", wasNullable = false, nowNullable = true) in changes,
        )
        assertEquals(3, changes.size, "expected exactly the three independent mutations; got $changes")
    }
}
