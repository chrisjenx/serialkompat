package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rename/move tracking (design §8): identity is by serial name, and a declared
 * rename lets the differ follow a moved type — pairing the two versions and
 * diffing their contents — instead of mis-reporting a wire-neutral move as a
 * delete + add (two false findings, one a spurious BREAK for persisted data).
 */
class SnapshotDifferRenameTest {
    private fun clazz(
        serialName: String,
        vararg elements: Element,
    ) = Contract(serialName, ContractKind.CLASS, elements = elements.toList())

    private fun diff(
        old: Snapshot,
        new: Snapshot,
        renames: Map<String, String> = emptyMap(),
    ) = SnapshotDiffer.diff(old, new, renames)

    @Test
    fun `without a rename, a moved type reads as remove plus add`() {
        val changes =
            diff(
                Snapshot(listOf(clazz("com.old.Order", Element("id", "String")))),
                Snapshot(listOf(clazz("com.new.Order", Element("id", "String")))),
            )
        assertTrue(Change.ContractRemoved("com.old.Order", ContractKind.CLASS) in changes)
        assertTrue(Change.ContractAdded("com.new.Order", ContractKind.CLASS) in changes)
    }

    @Test
    fun `a declared rename is followed as a move, not a delete plus add`() {
        val changes =
            diff(
                Snapshot(listOf(clazz("com.old.Order", Element("id", "String")))),
                Snapshot(listOf(clazz("com.new.Order", Element("id", "String")))),
                renames = mapOf("com.old.Order" to "com.new.Order"),
            )
        assertEquals(
            listOf(Change.ContractMoved("com.old.Order", "com.new.Order", ContractKind.CLASS)),
            changes,
        )
        assertFalse(changes.any { it is Change.ContractRemoved || it is Change.ContractAdded })
    }

    @Test
    fun `a rename whose endpoints both still exist is not a move and still diffs each`() {
        // A and B both present in old and new: the rename entry is stale/misused. B lost a
        // field, and that change must NOT be dropped just because a rename names B.
        val old =
            Snapshot(
                listOf(
                    clazz("A", Element("id", "String")),
                    clazz("B", Element("id", "String"), Element("keep", "String")),
                ),
            )
        val new = Snapshot(listOf(clazz("A", Element("id", "String")), clazz("B", Element("id", "String"))))
        val changes = diff(old, new, renames = mapOf("A" to "B"))
        assertFalse(changes.any { it is Change.ContractMoved })
        assertTrue(Change.ElementRemoved("B", Element("keep", "String")) in changes)
    }

    @Test
    fun `a stale rename to a missing target still reports the removal`() {
        val changes =
            diff(
                Snapshot(listOf(clazz("com.old.Order", Element("id", "String")))),
                Snapshot(emptyList()),
                renames = mapOf("com.old.Order" to "com.new.Order"),
            )
        assertTrue(Change.ContractRemoved("com.old.Order", ContractKind.CLASS) in changes)
    }

    private fun Snapshot.plus(vararg contracts: Contract) = Snapshot(this.contracts + contracts, config)

    private fun typeChanges(changes: List<Change>) = changes.filterIsInstance<Change.ElementTypeChanged>()

    @Test
    fun `a field referencing a renamed type is not a type change (#248)`() {
        val old = Snapshot(listOf(clazz("Wrapper", Element("order", "com.old.Order")), clazz("com.old.Order")))
        val new = Snapshot(listOf(clazz("Wrapper", Element("order", "com.new.Order")), clazz("com.new.Order")))
        val changes = diff(old, new, renames = mapOf("com.old.Order" to "com.new.Order"))
        assertEquals(emptyList(), typeChanges(changes))
        assertTrue(Change.ContractMoved("com.old.Order", "com.new.Order", ContractKind.CLASS) in changes)
    }

    @Test
    fun `a renamed type is followed inside List, Map and nullable references`() {
        val old =
            Snapshot(
                listOf(
                    clazz(
                        "Wrapper",
                        Element("all", "List<com.old.Order?>"),
                        Element("byId", "Map<kotlin.String,com.old.Order>"),
                    ),
                    clazz("com.old.Order"),
                ),
            )
        val new =
            Snapshot(
                listOf(
                    clazz(
                        "Wrapper",
                        Element("all", "List<com.new.Order?>"),
                        Element("byId", "Map<kotlin.String,com.new.Order>"),
                    ),
                    clazz("com.new.Order"),
                ),
            )
        assertEquals(emptyList(), typeChanges(diff(old, new, renames = mapOf("com.old.Order" to "com.new.Order"))))
    }

    @Test
    fun `a rename of a non-contract type name, such as a custom primitive serializer, is followed`() {
        // A custom KSerializer's descriptor name is only a type reference: no contract is recorded for it.
        val old = Snapshot(listOf(clazz("Event", Element("zone", "com.example.CachedTimeZone"))))
        val new = Snapshot(listOf(clazz("Event", Element("zone", "kotlinx.datetime.TimeZone"))))
        assertEquals(1, typeChanges(diff(old, new)).size)
        assertEquals(
            emptyList(),
            typeChanges(diff(old, new, renames = mapOf("com.example.CachedTimeZone" to "kotlinx.datetime.TimeZone"))),
        )
    }

    @Test
    fun `a type-name rename does not hide a change to a different type`() {
        val old = Snapshot(listOf(clazz("Event", Element("zone", "com.example.CachedTimeZone"))))
        val new = Snapshot(listOf(clazz("Event", Element("zone", "kotlin.Long"))))
        val renames = mapOf("com.example.CachedTimeZone" to "kotlinx.datetime.TimeZone")
        assertEquals(1, typeChanges(diff(old, new, renames)).size)
    }

    @Test
    fun `a rename between built-in kotlin types is never followed`() {
        val old = Snapshot(listOf(clazz("Event", Element("count", "kotlin.String"))))
        val new = Snapshot(listOf(clazz("Event", Element("count", "kotlin.Int"))))
        assertEquals(1, typeChanges(diff(old, new, renames = mapOf("kotlin.String" to "kotlin.Int"))).size)
    }

    @Test
    fun `a rename from a contract to a non-contract name is not followed (the wire shape changes)`() {
        val old =
            Snapshot(
                listOf(
                    clazz("Event", Element("zone", "com.example.Zone")),
                    clazz("com.example.Zone", Element("id", "kotlin.String")),
                ),
            )
        val new = Snapshot(listOf(clazz("Event", Element("zone", "kotlinx.datetime.TimeZone"))))
        val changes = diff(old, new, renames = mapOf("com.example.Zone" to "kotlinx.datetime.TimeZone"))
        assertEquals(1, typeChanges(changes).size)
        assertTrue(Change.ContractRemoved("com.example.Zone", ContractKind.CLASS) in changes)
    }

    @Test
    fun `a stale rename whose endpoints both still exist does not rewrite references`() {
        // A and B are both still on the wire; a field that switched from A to B is a real type change.
        val old = Snapshot(listOf(clazz("Wrapper", Element("x", "A")), clazz("A"), clazz("B")))
        val new = Snapshot(listOf(clazz("Wrapper", Element("x", "B")), clazz("A"), clazz("B")))
        assertEquals(1, typeChanges(diff(old, new, renames = mapOf("A" to "B"))).size)
    }

    @Test
    fun `a chain of renames is followed to its end for contracts and references`() {
        // A became B in one release and C in a later one; a recorded version still has A.
        val old = Snapshot(listOf(clazz("Wrapper", Element("x", "A")), clazz("A", Element("id", "kotlin.String"))))
        val new = Snapshot(listOf(clazz("Wrapper", Element("x", "C")), clazz("C", Element("id", "kotlin.String"))))
        val changes = diff(old, new, renames = mapOf("A" to "B", "B" to "C"))
        assertEquals(listOf(Change.ContractMoved("A", "C", ContractKind.CLASS)), changes)
    }

    @Test
    fun `a chain of type-name renames is followed, and a cycle is not an error`() {
        val old = Snapshot(listOf(clazz("Event", Element("zone", "com.example.ZoneV1"))))
        val new = Snapshot(listOf(clazz("Event", Element("zone", "com.example.ZoneV3"))))
        val chain = mapOf("com.example.ZoneV1" to "com.example.ZoneV2", "com.example.ZoneV2" to "com.example.ZoneV3")
        assertEquals(emptyList(), typeChanges(diff(old, new, chain)))
        val cycle = mapOf("com.example.P" to "com.example.Q", "com.example.Q" to "com.example.P")
        val loop = Snapshot(listOf(clazz("Event", Element("zone", "com.example.P"))))
        assertEquals(emptyList(), typeChanges(diff(loop, loop, cycle)))
    }

    @Test
    fun `a moved type still has its contents diffed under the new name`() {
        val changes =
            diff(
                Snapshot(listOf(clazz("com.old.Order", Element("id", "String")))),
                Snapshot(
                    listOf(clazz("com.new.Order", Element("id", "String"), Element("note", "String", optional = true))),
                ),
                renames = mapOf("com.old.Order" to "com.new.Order"),
            )
        assertTrue(Change.ContractMoved("com.old.Order", "com.new.Order", ContractKind.CLASS) in changes)
        assertTrue(Change.ElementAdded("com.new.Order", Element("note", "String", optional = true)) in changes)
    }
}
