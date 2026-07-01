package io.github.chrisjenx.serialkompat.core

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
