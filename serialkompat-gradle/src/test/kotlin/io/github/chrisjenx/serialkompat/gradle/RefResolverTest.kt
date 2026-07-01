package io.github.chrisjenx.serialkompat.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
