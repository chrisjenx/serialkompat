package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scope + coverage invariant (design §7, §9). A scope restricts which contracts
 * are checked (so a module that never crosses the wire can be excluded), but the
 * exclusion is *explicit and enumerable* — a type is never silently dropped from
 * analysis, which keeps the no-silent-exclusions guarantee honest.
 */
class ScopeTest {
    private fun clazz(serialName: String) = Contract(serialName, ContractKind.CLASS)

    @Test
    fun `the default scope includes everything`() {
        assertTrue(Scope().contains("com.example.Anything"))
    }

    @Test
    fun `include matches by prefix`() {
        val scope = Scope(include = listOf("com.example.wire."))
        assertTrue(scope.contains("com.example.wire.Order"))
        assertFalse(scope.contains("com.example.internal.Cache"))
    }

    @Test
    fun `exclude wins over include`() {
        val scope = Scope(include = listOf("com.example."), exclude = listOf("com.example.internal."))
        assertTrue(scope.contains("com.example.wire.Order"))
        assertFalse(scope.contains("com.example.internal.Cache"))
    }

    @Test
    fun `applying a scope partitions contracts into kept and excluded`() {
        val snapshot =
            Snapshot(
                listOf(
                    clazz("com.example.wire.Order"),
                    clazz("com.example.internal.Cache"),
                    clazz("com.example.wire.Payment"),
                ),
            )
        val coverage = snapshot.applyScope(Scope(exclude = listOf("com.example.internal.")))

        assertEquals(
            listOf("com.example.wire.Order", "com.example.wire.Payment"),
            coverage.inScope.contracts.map { it.serialName },
        )
        assertEquals(listOf("com.example.internal.Cache"), coverage.excluded)
    }

    @Test
    fun `applying a scope preserves the config`() {
        val config = SnapshotConfig(ignoreUnknownKeys = true)
        val coverage = Snapshot(listOf(clazz("a.B")), config).applyScope(Scope())
        assertEquals(config, coverage.inScope.config)
    }

    @Test
    fun `excluded contracts are enumerable, never silently dropped`() {
        val coverage = Snapshot(listOf(clazz("skip.Me"))).applyScope(Scope(include = listOf("keep.")))
        assertTrue(coverage.inScope.contracts.isEmpty())
        assertEquals(listOf("skip.Me"), coverage.excluded)
    }
}
