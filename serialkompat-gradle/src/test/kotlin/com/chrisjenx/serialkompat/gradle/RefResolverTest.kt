package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.gradle.git.GitCommands
import org.gradle.api.GradleException
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
    fun `with neither a property nor a configured ref, resolution is deferred to auto-detect`() {
        assertNull(resolveBaselineRef(null, configured = null))
        assertNull(resolveBaselineRef("  ", configured = "  "))
    }

    // --- default-branch auto-detection (issue #116) ---

    /** A fake git returning canned output; a missing key throws like a non-zero exit. */
    private class FakeGit(
        private val responses: Map<String, String>,
    ) : GitCommands {
        override fun run(vararg args: String): String {
            val key = args.joinToString(" ")
            return responses[key] ?: error("fake git: '$key' failed")
        }
    }

    @Test
    fun `auto-detect prefers the remote's declared default branch via origin HEAD`() {
        val git = FakeGit(mapOf("symbolic-ref --short refs/remotes/origin/HEAD" to "origin/master\n"))
        assertEquals("origin/master", resolveDefaultBranch(git))
    }

    @Test
    fun `auto-detect falls back to origin main when origin HEAD is unset`() {
        val git =
            FakeGit(
                mapOf(
                    // origin/HEAD unset → symbolic-ref absent (throws); origin/main resolves.
                    "rev-parse --verify --quiet origin/main^{commit}" to "abc\n",
                ),
            )
        assertEquals("origin/main", resolveDefaultBranch(git))
    }

    @Test
    fun `auto-detect falls back to origin master when origin main is absent`() {
        val git =
            FakeGit(
                mapOf(
                    "rev-parse --verify --quiet origin/master^{commit}" to "abc\n",
                ),
            )
        assertEquals("origin/master", resolveDefaultBranch(git))
    }

    @Test
    fun `auto-detect falls back to a local branch when there is no remote`() {
        val git = FakeGit(mapOf("rev-parse --verify --quiet main^{commit}" to "abc\n"))
        assertEquals("main", resolveDefaultBranch(git))
    }

    @Test
    fun `auto-detect fails with an actionable error when nothing resolves`() {
        val git = FakeGit(emptyMap())
        val error = assertFailsWith<GradleException> { resolveDefaultBranch(git) }
        assertEquals(true, error.message?.contains("baselineRef"))
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
