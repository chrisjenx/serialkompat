package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
