package io.github.chrisjenx.serialkompat.core

/**
 * Transitive compatibility over an append-only published history (design §5).
 *
 * Persisted data outlives code: a stored payload may have been written by *any*
 * previously published schema version, so the current schema must stay
 * compatible with the whole history — Confluent's `*_TRANSITIVE` semantics — not
 * merely the latest version. This checks the current schema against every
 * published version and aggregates the findings (deduplicated), so a break
 * against an old version is caught even when the newest version looks fine.
 */
public object TransitiveCompatibility {
    /**
     * Checks [current] against each snapshot in [history] (published versions)
     * under [profile]/[scope], returning the combined [Report]. [accepted] breaks
     * apply across all versions.
     */
    public fun checkAgainstHistory(
        current: Snapshot,
        history: List<Snapshot>,
        profile: CompatibilityProfile = CompatibilityProfile(),
        scope: Scope = Scope(),
        accepted: List<AcceptedBreak> = emptyList(),
    ): Report {
        // Aggregate across versions, keeping the *worst-case* severity per finding key:
        // the same change can classify differently against different published configs, so
        // dedup must not keep an arbitrary (first) one and silently drop a more-severe verdict.
        val findings =
            history
                .flatMap { published ->
                    CompatibilityEngine.check(published, current, profile, scope, accepted).findings
                }.groupBy { listOf(it.rule, it.direction, it.contract, it.detail) }
                .map { (_, group) -> group.maxBy { it.severity.ordinal } }
        return Report(findings, accepted)
    }
}
