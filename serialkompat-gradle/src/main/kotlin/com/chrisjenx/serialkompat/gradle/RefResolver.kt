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

private const val ACCEPTED_BREAK_FORMAT = "<type> <RULE> [BACKWARD|FORWARD|FULL [member]]"

/**
 * Parses an accepted-break spec from the `serialkompat { acceptedBreaks }` extension.
 * Format: `"<type> <RULE> [BACKWARD|FORWARD|FULL [member]]"` — e.g. `"com.example.Order PROPERTY_REMOVED"`,
 * `"com.example.Order PROPERTY_REMOVED FORWARD"` or `"com.example.Order PROPERTY_REMOVED FULL note"`.
 * An omitted direction (or `FULL`) accepts both. The direction is always the third token, so a member
 * needs one; the member is the rest of the spec, matched exactly against the name the finding quotes
 * (#120). Omit it to accept the rule for every member of the type.
 */
internal fun parseAcceptedBreak(spec: String): AcceptedBreak {
    val parts = spec.trim().split(Regex("\\s+"), limit = 4)
    require(parts.size >= 2) {
        "serialkompat: acceptedBreak '$spec' must be '$ACCEPTED_BREAK_FORMAT'"
    }
    val direction =
        parts.getOrNull(2)?.let { token ->
            requireNotNull(CompatibilityDirection.entries.firstOrNull { it.name == token.uppercase() }) {
                "serialkompat: acceptedBreak '$spec': '$token' is not a direction. The format is " +
                    "'$ACCEPTED_BREAK_FORMAT'; a member goes after the direction."
            }
        }
    return AcceptedBreak(type = parts[0], rule = parts[1], direction = direction, member = parts.getOrNull(3))
}
