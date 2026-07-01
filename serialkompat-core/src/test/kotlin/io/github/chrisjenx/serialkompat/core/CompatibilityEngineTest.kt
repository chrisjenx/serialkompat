package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The end-to-end engine (design §3): scope → diff → classify → report, the single
 * entry point that ties the pure pipeline together. The Gradle tasks and the CLI
 * feed it two snapshots and a policy; everything downstream is shared.
 */
class CompatibilityEngineTest {
    private fun clazz(
        serialName: String,
        vararg elements: Element,
    ) = Contract(serialName, ContractKind.CLASS, elements = elements.toList())

    private fun check(
        baseline: Snapshot,
        current: Snapshot,
        profile: CompatibilityProfile = CompatibilityProfile(),
        scope: Scope = Scope(),
        accepted: List<AcceptedBreak> = emptyList(),
        renames: Map<String, String> = emptyMap(),
    ) = CompatibilityEngine.check(baseline, current, profile, scope, accepted, renames)

    @Test
    fun `identical snapshots produce a clean, passing report`() {
        val snapshot = Snapshot(listOf(clazz("com.example.Order", Element("id", "kotlin.String"))))
        val report = check(snapshot, snapshot)
        assertTrue(report.findings.isEmpty())
        assertFalse(report.shouldFail(Severity.BREAK))
    }

    @Test
    fun `removing a required field is a failing break end-to-end`() {
        val report =
            check(
                baseline = Snapshot(listOf(clazz("com.example.Order", Element("id", "kotlin.String")))),
                current = Snapshot(listOf(clazz("com.example.Order"))),
            )
        assertTrue(report.shouldFail(Severity.BREAK))
        assertTrue(report.active.any { it.rule == Rules.PROPERTY_REMOVED })
    }

    @Test
    fun `an excluded type's changes are not reported`() {
        val report =
            check(
                baseline = Snapshot(listOf(clazz("com.example.internal.Cache", Element("id", "kotlin.String")))),
                current = Snapshot(listOf(clazz("com.example.internal.Cache"))),
                scope = Scope(exclude = listOf("com.example.internal.")),
            )
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `an accepted break is acknowledged, not failing`() {
        val report =
            check(
                baseline = Snapshot(listOf(clazz("com.example.Order", Element("id", "kotlin.String")))),
                current = Snapshot(listOf(clazz("com.example.Order"))),
                accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED)),
            )
        assertFalse(report.shouldFail(Severity.BREAK))
        assertTrue(report.active.isEmpty())
        assertTrue(report.acknowledged.isNotEmpty())
    }

    @Test
    fun `a BACKWARD-only profile ignores forward-only breaks`() {
        // Adding a required field breaks backward; a widening breaks only forward.
        val report =
            check(
                baseline = Snapshot(listOf(clazz("com.example.Order", Element("amount", "kotlin.Int")))),
                current = Snapshot(listOf(clazz("com.example.Order", Element("amount", "kotlin.Long")))),
                profile = CompatibilityProfile(direction = CompatibilityDirection.BACKWARD),
            )
        // Int -> Long is backward-safe, so a backward-only check passes.
        assertFalse(report.shouldFail(Severity.BREAK))
    }

    @Test
    fun `a declared rename is not reported as a break`() {
        val report =
            check(
                baseline = Snapshot(listOf(clazz("com.old.Order", Element("id", "kotlin.String")))),
                current = Snapshot(listOf(clazz("com.new.Order", Element("id", "kotlin.String")))),
                renames = mapOf("com.old.Order" to "com.new.Order"),
            )
        assertFalse(report.shouldFail(Severity.BREAK))
    }

    @Test
    fun `config tightening surfaces as a finding`() {
        val report =
            check(
                baseline = Snapshot(config = SnapshotConfig(ignoreUnknownKeys = true)),
                current = Snapshot(config = SnapshotConfig(ignoreUnknownKeys = false)),
            )
        assertTrue(report.active.any { it.rule == Rules.CONFIG_READER_STRICTNESS })
    }
}
