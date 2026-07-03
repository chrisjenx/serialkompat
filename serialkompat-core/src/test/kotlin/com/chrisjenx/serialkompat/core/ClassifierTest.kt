package com.chrisjenx.serialkompat.core

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
    fun `remove optional field — a lenient new reader still warns (silent data-loss)`() {
        // A tolerant reader decodes without error but silently DROPS the removed field's value,
        // so this is a silent-data-loss WARN, never SAFE (design §7: "silent data-loss = WARN").
        // It is also what surfaces a field rename, which the differ decomposes into remove + add.
        val f = classify(Change.ElementRemoved("T", element("x", optional = true)), new = lenient)
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.BACKWARD))
    }

    @Test
    fun `remove required field — a lenient new reader warns, never safe`() {
        // Even a tolerant reader silently drops a removed required field's value → WARN, never SAFE.
        val f = classify(Change.ElementRemoved("T", element("x", optional = false)), new = lenient)
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.BACKWARD))
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
    fun `non-null to nullable — backward safe, forward breaks when nulls are emitted`() {
        // Default explicitNulls=true: the writer emits null, so an old non-null reader chokes → BREAK.
        val f = classify(Change.ElementNullabilityChanged("T", "x", wasNullable = false, nowNullable = true))
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `non-null to nullable — forward only a WARN when the writer omits nulls`() {
        // explicitNulls=false: the writer omits a null field, so whether an old reader chokes
        // depends on that field's optionality (unknowable here) — conditional, so WARN not BREAK.
        val f =
            classify(
                Change.ElementNullabilityChanged("T", "x", wasNullable = false, nowNullable = true),
                new = SnapshotConfig(explicitNulls = false),
            )
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.FORWARD))
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
    fun `enum add value — forward breaks a strict reader, backward safe`() {
        val strictReader = classify(Change.EnumValueAdded("E", "C"))
        assertNull(strictReader.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, strictReader.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `enum add value — WARN only when coercing AND every reader field can fall back to a default`() {
        // coerceInputValues rescues an unknown value ONLY for a field with a default. The differ
        // records whether that holds for every field reading this enum (baselineFieldsCoercible);
        // the classifier combines it with the reader's coerce setting (#129).
        val coerce = SnapshotConfig(coerceInputValues = true)

        // Coercing reader + a defaulted direct field → the unknown value coerces to that default: WARN.
        val coercible = classify(Change.EnumValueAdded("E", "C", baselineFieldsCoercible = true), old = coerce)
        assertEquals(Severity.WARN, coercible.severity(CompatibilityDirection.FORWARD))

        // Coercing reader but a required / nested / top-level use (nothing to coerce to) → still BREAK.
        val notCoercible = classify(Change.EnumValueAdded("E", "C", baselineFieldsCoercible = false), old = coerce)
        assertEquals(Severity.BREAK, notCoercible.severity(CompatibilityDirection.FORWARD))

        // A strict reader never coerces, so even a coercible field is a BREAK.
        val strict = classify(Change.EnumValueAdded("E", "C", baselineFieldsCoercible = true))
        assertEquals(Severity.BREAK, strict.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `enum remove value — backward breaks, forward safe`() {
        val f = classify(Change.EnumValueRemoved("E", "C"))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertNull(f.severity(CompatibilityDirection.FORWARD))
    }

    // --- @JsonNames aliases ----------------------------------------------------

    @Test
    fun `dropping a JsonNames alias warns backward, adding one is safe`() {
        val removed =
            classify(Change.ElementJsonNamesChanged("T", "x", oldAliases = listOf("legacy"), newAliases = emptyList()))
        // A reader that stops accepting a key it used to accept can break a peer still sending it.
        assertEquals(Severity.WARN, removed.severity(CompatibilityDirection.BACKWARD))
        assertNull(removed.severity(CompatibilityDirection.FORWARD))

        val added =
            classify(Change.ElementJsonNamesChanged("T", "x", oldAliases = emptyList(), newAliases = listOf("extra")))
        assertTrue(added.isEmpty()) // widening the accepted key set is safe both ways
    }

    // --- Polymorphism ----------------------------------------------------------

    @Test
    fun `add subtype — forward breaks an old reader, backward safe`() {
        val f = classify(Change.SubtypeAdded("P", Subtype("b", "B")))
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `add subtype with a base default deserializer downgrades the forward break to WARN`() {
        // A base that registered a polymorphic default deserializer lets an old (forward) reader
        // coerce the new subtype's unknown discriminator to the sentinel instead of throwing — a
        // silent substitution, so WARN not BREAK; backward stays safe (#128).
        val f = classify(Change.SubtypeAdded("P", Subtype("b", "B"), baseHadDefaultDeserializer = true))
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.FORWARD))
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

    // --- Numeric type changes --------------------------------------------------

    @Test
    fun `a numeric widening reads old data (backward safe) but breaks a strict old reader (forward)`() {
        val f = classify(Change.ElementTypeChanged("T", "n", "kotlin.Byte", "kotlin.Int"))
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `a numeric narrowing breaks both directions`() {
        val f = classify(Change.ElementTypeChanged("T", "n", "kotlin.Long", "kotlin.Int"))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, f.severity(CompatibilityDirection.FORWARD))
    }

    // --- Coverage gaps (OPAQUE) ------------------------------------------------
    // Unanalysable != safe: an opaque type the gate can't verify must surface as a
    // WARN in every checked direction, never be silently assumed compatible (§10).

    @Test
    fun `a coverage gap warns in both directions`() {
        val f = classify(Change.CoverageGap("com.example.Blob"))
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `a coverage gap under a backward-only profile warns backward only`() {
        val f =
            classify(
                Change.CoverageGap("com.example.Blob"),
                profile = CompatibilityProfile(direction = CompatibilityDirection.BACKWARD),
            )
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.BACKWARD))
        assertNull(f.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `a coverage gap under a forward-only profile warns forward only`() {
        val f =
            classify(
                Change.CoverageGap("com.example.Blob"),
                profile = CompatibilityProfile(direction = CompatibilityDirection.FORWARD),
            )
        assertNull(f.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.WARN, f.severity(CompatibilityDirection.FORWARD))
    }
}
