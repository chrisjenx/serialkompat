package io.github.chrisjenx.serialkompat.gradle

import io.github.chrisjenx.serialkompat.core.CompatibilityDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `serialkompatCheckAgainst` lets you compare against an ad-hoc ref without
 * editing config, e.g. `-Pserialkompat.ref=v1.2.0`; it falls back to the
 * configured `baselineRef` when the property is absent or blank.
 */
class RefResolverTest {
    @Test
    fun `an explicit property ref wins`() {
        assertEquals("v1.2.0", resolveBaselineRef("v1.2.0", configured = "origin/main"))
    }

    @Test
    fun `a null property falls back to the configured ref`() {
        assertEquals("origin/main", resolveBaselineRef(null, configured = "origin/main"))
    }

    @Test
    fun `a blank property falls back to the configured ref`() {
        assertEquals("origin/main", resolveBaselineRef("  ", configured = "origin/main"))
    }

    @Test
    fun `the property is coerced to a trimmed string`() {
        assertEquals("release/2.0", resolveBaselineRef("  release/2.0  ", configured = "origin/main"))
    }

    @Test
    fun `an accepted break without a direction accepts both directions`() {
        val accepted = parseAcceptedBreak("com.example.Order PROPERTY_REMOVED")
        assertEquals("com.example.Order", accepted.type)
        assertEquals("PROPERTY_REMOVED", accepted.rule)
        assertNull(accepted.direction)
    }

    @Test
    fun `an accepted break can pin a single direction`() {
        assertEquals(
            CompatibilityDirection.FORWARD,
            parseAcceptedBreak("com.example.Order PROPERTY_REMOVED FORWARD").direction,
        )
    }

    @Test
    fun `an accepted break spec missing the rule is rejected`() {
        assertFailsWith<IllegalArgumentException> { parseAcceptedBreak("com.example.Order") }
    }
}
