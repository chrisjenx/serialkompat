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
