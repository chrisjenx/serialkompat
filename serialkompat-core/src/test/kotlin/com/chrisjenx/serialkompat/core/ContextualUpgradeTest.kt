package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Before contextual resolution, the extractor recorded every `@Contextual` field as kotlinx's
 * internal placeholder type (`kotlinx.serialization.ContextualSerializer<Stamp>`) plus an OPAQUE
 * contract of that name. A baseline recorded then, diffed against a resolved snapshot, must read as
 * a coverage gain, not as a type change and a removed type.
 */
class ContextualUpgradeTest {
    private val placeholder = "kotlinx.serialization.ContextualSerializer<Stamp>"

    private val before =
        Snapshot(
            listOf(
                Contract(
                    "Holder",
                    ContractKind.CLASS,
                    listOf(Element("stamp", placeholder), Element("stamps", "List<$placeholder>")),
                ),
                Contract(placeholder, ContractKind.OPAQUE),
            ),
        )

    private val after =
        Snapshot(
            listOf(
                Contract(
                    "Holder",
                    ContractKind.CLASS,
                    listOf(Element("stamp", "com.example.Stamp"), Element("stamps", "List<com.example.Stamp>")),
                ),
            ),
        )

    private fun findings(
        old: Snapshot,
        new: Snapshot,
    ): List<Finding> = Classifier().classify(SnapshotDiffer.diff(old, new))

    @Test
    fun `an unresolved contextual placeholder becoming resolved is a coverage gap, not a break`() {
        val f = findings(before, after)
        assertTrue(f.none { it.severity == Severity.BREAK }, f.toString())
        assertTrue(f.none { it.rule == Rules.CONTRACT_REMOVED || it.rule == Rules.PROPERTY_TYPE_CHANGED }, f.toString())
        assertEquals(setOf(Rules.COVERAGE_GAP), f.map { it.rule }.toSet(), f.toString())
        assertTrue(f.all { it.severity == Severity.WARN })
    }

    @Test
    fun `a resolved contextual type losing its serializer is still a break`() {
        val f = findings(after, before)
        assertTrue(f.any { it.rule == Rules.PROPERTY_TYPE_CHANGED && it.severity == Severity.BREAK }, f.toString())
        assertTrue(f.any { it.rule == Rules.COVERAGE_GAP }, f.toString())
    }

    @Test
    fun `a placeholder that stays a placeholder is just the usual gap`() {
        val f = findings(before, before)
        assertEquals(listOf(Rules.COVERAGE_GAP), f.map { it.rule }.distinct(), f.toString())
    }
}
