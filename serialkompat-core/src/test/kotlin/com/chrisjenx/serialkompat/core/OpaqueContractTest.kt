package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPAQUE coverage-gap contracts must survive the codec and the differ, and — the
 * load-bearing invariant — must never be silently treated as safe (design §10):
 * an unanalysable type the gate cannot verify is surfaced as a WARN every run.
 */
class OpaqueContractTest {
    @Test
    fun `an opaque contract round-trips through the text codec`() {
        val snapshot = Snapshot(listOf(Contract("com.example.Weird", ContractKind.OPAQUE)))
        assertEquals(snapshot, SnapshotFormat.parse(SnapshotFormat.serialize(snapshot)))
    }

    @Test
    fun `an opaque contract present unchanged is still surfaced as a coverage gap`() {
        // Both sides opaque (e.g. a custom serializer whose wire shape we can't see) look
        // structurally identical, so the gate must flag the gap rather than pass silently.
        val snapshot = Snapshot(listOf(Contract("X", ContractKind.OPAQUE)))
        assertEquals(listOf(Change.CoverageGap("X")), SnapshotDiffer.diff(snapshot, snapshot))
    }

    @Test
    fun `an added opaque contract is reported and surfaced as a coverage gap`() {
        val changes = SnapshotDiffer.diff(Snapshot(), Snapshot(listOf(Contract("X", ContractKind.OPAQUE))))
        assertTrue(Change.ContractAdded("X", ContractKind.OPAQUE) in changes)
        assertTrue(Change.CoverageGap("X") in changes)
    }

    @Test
    fun `an opaque contract is never classified safe`() {
        val snapshot = Snapshot(listOf(Contract("X", ContractKind.OPAQUE)))
        val findings = Classifier().classify(SnapshotDiffer.diff(snapshot, snapshot))
        assertTrue(findings.any { it.severity == Severity.WARN }, "unanalysable must not be silently safe")
    }

    @Test
    fun `a baseline-opaque contract now analysed is a coverage gap, not a removal`() {
        // The baseline type was a coverage gap (e.g. a history snapshot from an older extractor, or a
        // type a code change made analysable). Its old wire shape was never seen, so there's nothing to
        // compare: not a removal (a false BREAK), but not silently safe either. One WARN gap this run.
        val old = Snapshot(listOf(Contract("X", ContractKind.OPAQUE)))
        val new = Snapshot(listOf(Contract("X", ContractKind.CLASS, elements = listOf(Element("id", "String")))))
        val changes = SnapshotDiffer.diff(old, new)
        assertEquals(listOf(Change.CoverageGap("X")), changes)

        val findings = Classifier().classify(changes)
        assertFalse(findings.any { it.severity == Severity.BREAK }, "a coverage gain must not BREAK: $findings")
        assertTrue(findings.all { it.rule == Rules.COVERAGE_GAP && it.severity == Severity.WARN }, "$findings")
        assertEquals(2, findings.size, "expected a WARN gap in both directions: $findings")
    }

    @Test
    fun `a baseline-opaque contract resolving to any analysed kind is a single coverage gap`() {
        val old = Snapshot(listOf(Contract("X", ContractKind.OPAQUE)))
        val analysed =
            listOf(
                Contract("X", ContractKind.OBJECT),
                Contract("X", ContractKind.ENUM, enumValues = listOf("A")),
                Contract("X", ContractKind.SEALED, discriminator = "type"),
                Contract("X", ContractKind.POLYMORPHIC, discriminator = "type"),
            )
        for (after in analysed) {
            assertEquals(listOf(Change.CoverageGap("X")), SnapshotDiffer.diff(old, Snapshot(listOf(after))), "$after")
        }
    }

    @Test
    fun `a renamed baseline-opaque contract now analysed is a move plus a coverage gap`() {
        val old = Snapshot(listOf(Contract("Old", ContractKind.OPAQUE)))
        val new = Snapshot(listOf(Contract("New", ContractKind.CLASS)))
        assertEquals(
            listOf(Change.ContractMoved("Old", "New", ContractKind.CLASS), Change.CoverageGap("New")),
            SnapshotDiffer.diff(old, new, renames = mapOf("Old" to "New")),
        )
    }

    @Test
    fun `an analysed contract becoming opaque stays loud - removal break plus coverage gap`() {
        // A coverage LOSS must never read as safe. Today it is remove + add (BREAK) plus the gap scan's
        // WARN — overcautious (the opaque form may be wire-compatible) but loud, which is the invariant.
        val old = Snapshot(listOf(Contract("X", ContractKind.CLASS, elements = listOf(Element("id", "String")))))
        val new = Snapshot(listOf(Contract("X", ContractKind.OPAQUE)))
        val changes = SnapshotDiffer.diff(old, new)
        assertEquals(
            listOf(
                Change.ContractRemoved("X", ContractKind.CLASS),
                Change.ContractAdded("X", ContractKind.OPAQUE),
                Change.CoverageGap("X"),
            ),
            changes,
        )
        val findings = Classifier().classify(changes)
        assertTrue(findings.any { it.rule == Rules.CONTRACT_REMOVED && it.severity == Severity.BREAK }, "$findings")
        assertTrue(findings.any { it.rule == Rules.COVERAGE_GAP && it.severity == Severity.WARN }, "$findings")
    }
}
