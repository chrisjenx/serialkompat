package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Classifying a tracked move (design §8): moving a plain type is wire-neutral
 * (its class name isn't on the wire), but for a sealed/polymorphic type the
 * serial name *is* the discriminator value, so the same move is a break.
 */
class ClassifierMoveTest {
    private fun classify(change: Change) = Classifier().classify(listOf(change))

    @Test
    fun `moving a plain class is safe and produces no failing finding`() {
        assertTrue(classify(Change.ContractMoved("com.old.Order", "com.new.Order", ContractKind.CLASS)).isEmpty())
    }

    @Test
    fun `moving an enum is safe`() {
        assertTrue(classify(Change.ContractMoved("A", "B", ContractKind.ENUM)).isEmpty())
    }

    @Test
    fun `moving a sealed type changes the discriminator value — BREAK`() {
        val findings = classify(Change.ContractMoved("com.old.Payment", "com.new.Payment", ContractKind.SEALED))
        assertEquals(Severity.BREAK, findings.first { it.direction == CompatibilityDirection.BACKWARD }.severity)
        assertEquals(Rules.DISCRIMINATOR_VALUE_CHANGED, findings.first().rule)
    }

    @Test
    fun `moving a polymorphic type is a BREAK`() {
        val findings = classify(Change.ContractMoved("A", "B", ContractKind.POLYMORPHIC))
        assertTrue(findings.any { it.severity == Severity.BREAK })
    }
}
