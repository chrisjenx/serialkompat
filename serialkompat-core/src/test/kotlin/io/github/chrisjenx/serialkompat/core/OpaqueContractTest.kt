package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** OPAQUE coverage-gap contracts must survive the codec and the differ (design §10). */
class OpaqueContractTest {
    @Test
    fun `an opaque contract round-trips through the text codec`() {
        val snapshot = Snapshot(listOf(Contract("com.example.Weird", ContractKind.OPAQUE)))
        assertEquals(snapshot, SnapshotFormat.parse(SnapshotFormat.serialize(snapshot)))
    }

    @Test
    fun `two opaque contracts of the same name produce no change`() {
        val snapshot = Snapshot(listOf(Contract("X", ContractKind.OPAQUE)))
        assertEquals(emptyList(), SnapshotDiffer.diff(snapshot, snapshot))
    }

    @Test
    fun `an added opaque contract is reported as a contract addition`() {
        val changes =
            SnapshotDiffer.diff(
                Snapshot(),
                Snapshot(listOf(Contract("X", ContractKind.OPAQUE))),
            )
        assertEquals(listOf(Change.ContractAdded("X", ContractKind.OPAQUE)), changes)
    }
}
