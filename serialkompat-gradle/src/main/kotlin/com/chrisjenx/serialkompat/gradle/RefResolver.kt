package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.AcceptedBreak
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.gradle.git.GitCommands
import org.gradle.api.GradleException

/**
 * Resolves the baseline ref to check against: an explicit `-Pserialkompat.ref`
 * value (as passed to `serialkompatCheckAgainst`) wins; a null or blank value
 * falls back to the [configured] `baselineRef`. Returns `null` when neither is
 * set, deferring to [resolveDefaultBranch] at execution time (issue #116).
 */
internal fun resolveBaselineRef(
    property: Any?,
    configured: String?,
): String? =
    property?.toString()?.trim()?.takeIf(String::isNotEmpty)
        ?: configured?.trim()?.takeIf(String::isNotEmpty)

/**
 * Auto-detects the default branch to use as the baseline when the consumer
 * hasn't configured `baselineRef` (issue #116). Resolution order, most to least
 * authoritative:
 *
 * 1. `origin/HEAD` — the remote's own declared default branch (correct even for
 *    non-`main`/`master` defaults).
 * 2. `origin/main`, then `origin/master` — the conventional remote branches, for
 *    clones where `origin/HEAD` isn't set (common on shallow CI checkouts).
 * 3. local `main`, then `master` — no-remote repos and tests.
 *
 * Fail-closed: if none resolve, throws with an actionable message rather than
 * silently guessing, mirroring the baseline's fail-closed contract (design §5).
 */
internal fun resolveDefaultBranch(git: GitCommands): String {
    runCatching { git.run("symbolic-ref", "--short", "refs/remotes/origin/HEAD") }
        .getOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }

    for (ref in listOf("origin/main", "origin/master", "main", "master")) {
        if (refExists(git, ref)) return ref
    }

    throw GradleException(
        "serialkompat: could not auto-detect a default baseline branch (looked for origin/HEAD, " +
            "origin/main, origin/master, main, master). Set serialkompat { baselineRef.set(\"<ref>\") } " +
            "or pass -Pserialkompat.ref=<ref>.",
    )
}

/** True if [ref] resolves to a commit. `--verify --quiet` exits non-zero (→ throw) when it doesn't. */
private fun refExists(
    git: GitCommands,
    ref: String,
): Boolean = runCatching { git.run("rev-parse", "--verify", "--quiet", "$ref^{commit}") }.isSuccess

/**
 * Parses an accepted-break spec from the `serialkompat { acceptedBreaks }` extension.
 * Format: `"<serialName> <RULE> [DIRECTION]"` — e.g. `"com.example.Order PROPERTY_REMOVED"`
 * or `"com.example.Order PROPERTY_REMOVED FORWARD"`. An omitted direction accepts both.
 */
internal fun parseAcceptedBreak(spec: String): AcceptedBreak {
    val parts = spec.trim().split(Regex("\\s+"))
    require(parts.size >= 2) {
        "serialkompat: acceptedBreak '$spec' must be '<serialName> <RULE> [BACKWARD|FORWARD]'"
    }
    val direction = parts.getOrNull(2)?.let { CompatibilityDirection.valueOf(it.uppercase()) }
    return AcceptedBreak(type = parts[0], rule = parts[1], direction = direction)
}
