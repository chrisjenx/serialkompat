package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the rule matrix (design §7): each structural [Change], under a
 * [CompatibilityProfile] and the reader/writer [SnapshotConfig]s, yields the
 * expected per-direction [Severity]. The classifier only reports actionable
 * findings (WARN/BREAK); a direction that is SAFE produces no finding.
 */
class ClassifierTest {
    private val strict = SnapshotConfig() // ignoreUnknownKeys=false, encodeDefaults=false, coerceInputValues=false
    private val lenient = SnapshotConfig(ignoreUnknownKeys = true)

    private fun classify(
        change: Change,
        profile: CompatibilityProfile = CompatibilityProfile(),
        old: SnapshotConfig = strict,
        new: SnapshotConfig = strict,
    ): List<Finding> = Classifier(profile).classify(listOf(change), old, new)

    /** Severity reported for [direction], or null if that direction was SAFE / unchecked. */
    private fun List<Finding>.severity(direction: CompatibilityDirection): Severity? =
        singleOrNull { it.direction == direction }?.severity

    private fun element(
        name: String,
        type: String = "kotlin.String",
        optional: Boolean = false,
        nullable: Boolean = false,
    ) = Element(name, type, optional = optional, nullable = nullable)

    // --- Added / removed fields ------------------------------------------------

    @Test
    fun `add optional field — backward safe, forward breaks a strict reader`() {
        val f = classify(Change.ElementAdded("T", element("x", optional = true)))
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `add optional field — a lenient old reader tolerates it forward`() {
        val f = classify(Change.ElementAdded("T", element("x", optional = true)), old = lenient)
        assertNull(f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `add required field — backward breaks (missing field)`() {
        val f = classify(Change.ElementAdded("T", element("x", optional = false)))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `remove optional field — backward breaks a strict reader, forward safe`() {
        val f = classify(Change.ElementRemoved("T", element("x", optional = true)))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertNull(f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `remove optional field — a lenient new reader tolerates it backward`() {
        val f = classify(Change.ElementRemoved("T", element("x", optional = true)), new = lenient)
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
    }

    @Test
    fun `remove required field — backward config-dependent, forward breaks`() {
        val f = classify(Change.ElementRemoved("T", element("x", optional = false)))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    // --- Optionality -----------------------------------------------------------

    @Test
    fun `optional to required — backward breaks, forward safe`() {
        val f = classify(Change.ElementOptionalityChanged("T", "x", wasOptional = true, nowOptional = false))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertNull(f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `required to optional — forward breaks unless the new writer encodes defaults`() {
        val strictWriter = classify(Change.ElementOptionalityChanged("T", "x", wasOptional = false, nowOptional = true))
        assertNull(strictWriter.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, strictWriter.severity(CompatibilityDirection.FORWARD))

        val encodingWriter =
            classify(
                Change.ElementOptionalityChanged("T", "x", wasOptional = false, nowOptional = true),
                new = SnapshotConfig(encodeDefaults = true),
            )
        assertNull(encodingWriter.severity(CompatibilityDirection.FORWARD))
    }

    // --- Nullability -----------------------------------------------------------

    @Test
    fun `non-null to nullable — backward safe, forward breaks an old reader`() {
        val f = classify(Change.ElementNullabilityChanged("T", "x", wasNullable = false, nowNullable = true))
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `nullable to non-null — backward breaks on old null, forward safe`() {
        val f = classify(Change.ElementNullabilityChanged("T", "x", wasNullable = true, nowNullable = false))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertNull(f.severity(CompatibilityDirection.FORWARD))
    }

    // --- Type changes ----------------------------------------------------------

    @Test
    fun `incompatible type change breaks both directions`() {
        val f = classify(Change.ElementTypeChanged("T", "x", "kotlin.String", "kotlin.Int"))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `numeric widening Int to Long — backward safe, forward breaks`() {
        val f = classify(Change.ElementTypeChanged("T", "x", "kotlin.Int", "kotlin.Long"))
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    // --- Enums -----------------------------------------------------------------

    @Test
    fun `enum add value — forward breaks a strict reader, safe when coercing`() {
        val strictReader = classify(Change.EnumValueAdded("E", "C"))
        assertNull(strictReader.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, strictReader.severity(CompatibilityDirection.FORWARD))

        val coercing = classify(Change.EnumValueAdded("E", "C"), old = SnapshotConfig(coerceInputValues = true))
        assertNull(coercing.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `enum remove value — backward breaks, forward safe`() {
        val f = classify(Change.EnumValueRemoved("E", "C"))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertNull(f.severity(CompatibilityDirection.FORWARD))
    }

    // --- Polymorphism ----------------------------------------------------------

    @Test
    fun `add subtype — forward breaks an old reader, backward safe`() {
        val f = classify(Change.SubtypeAdded("P", Subtype("b", "B")))
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `remove subtype — backward breaks, forward safe`() {
        val f = classify(Change.SubtypeRemoved("P", Subtype("b", "B")))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertNull(f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `discriminator change breaks both directions`() {
        val f = classify(Change.DiscriminatorChanged("P", "type", "kind"))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    // --- Contracts -------------------------------------------------------------

    @Test
    fun `removing a whole contract breaks both directions`() {
        val f = classify(Change.ContractRemoved("T", ContractKind.CLASS))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `adding a whole contract is safe`() {
        assertTrue(classify(Change.ContractAdded("T", ContractKind.CLASS)).isEmpty())
    }

    // --- Direction filtering & scope -------------------------------------------

    @Test
    fun `BACKWARD profile reports only backward findings`() {
        val f =
            classify(
                Change.ElementTypeChanged("T", "x", "kotlin.String", "kotlin.Int"),
                profile = CompatibilityProfile(direction = CompatibilityDirection.BACKWARD),
            )
        assertEquals(1, f.size)
        assertEquals(CompatibilityDirection.BACKWARD, f.single().direction)
    }

    @Test
    fun `FORWARD profile reports only forward findings`() {
        val f =
            classify(
                Change.ElementTypeChanged("T", "x", "kotlin.String", "kotlin.Int"),
                profile = CompatibilityProfile(direction = CompatibilityDirection.FORWARD),
            )
        assertEquals(1, f.size)
        assertEquals(CompatibilityDirection.FORWARD, f.single().direction)
    }

    @Test
    fun `STRICT reader tolerance ignores a lenient config`() {
        val f =
            classify(
                Change.ElementAdded("T", element("x", optional = true)),
                profile = CompatibilityProfile(readerTolerance = ReaderTolerance.STRICT),
                old = lenient,
            )
        // Even though the old reader config is lenient, STRICT overrides it → forward breaks.
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `config changes are not classified here (deferred to #13)`() {
        assertTrue(classify(Change.ConfigChanged("ignoreUnknownKeys", "false", "true")).isEmpty())
    }

    @Test
    fun `findings carry a rule name, contract, and fix hint`() {
        val finding =
            classify(Change.ElementAdded("com.example.T", element("x", optional = false)))
                .first { it.direction == CompatibilityDirection.BACKWARD }
        assertEquals("com.example.T", finding.contract)
        assertTrue(finding.rule.isNotBlank())
        assertTrue(finding.fixHint?.isNotBlank() == true)
    }
}
