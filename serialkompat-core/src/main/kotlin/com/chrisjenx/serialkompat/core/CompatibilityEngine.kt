package com.chrisjenx.serialkompat.core

/**
 * The end-to-end compatibility engine (design §3): the single entry point that
 * ties the pure pipeline together — restrict to [Scope], diff, classify under a
 * [CompatibilityProfile], and fold declared renames and accepted breaks into a
 * [Report]. Every front end (Gradle task, CLI) feeds it two snapshots plus policy
 * and shares this one implementation, regardless of where the snapshots came from.
 */
public object CompatibilityEngine {
    /**
     * Checks [current] against the [baseline].
     *
     * @param profile the direction(s) and reader tolerance to enforce.
     * @param scope restricts which contracts are checked.
     * @param accepted breaks that are sanctioned (downgraded to acknowledged).
     * @param renames declared serial-name moves (old → new), so a move isn't a break.
     */
    public fun check(
        baseline: Snapshot,
        current: Snapshot,
        profile: CompatibilityProfile = CompatibilityProfile(),
        scope: Scope = Scope(),
        accepted: List<AcceptedBreak> = emptyList(),
        renames: Map<String, String> = emptyMap(),
    ): Report {
        val baselineInScope = baseline.applyScope(scope).inScope
        val currentInScope = current.applyScope(scope).inScope
        val changes = SnapshotDiffer.diff(baselineInScope, currentInScope, renames)
        val findings = Classifier(profile).classify(changes, baselineInScope.config, currentInScope.config)
        return Report(findings, accepted)
    }
}
