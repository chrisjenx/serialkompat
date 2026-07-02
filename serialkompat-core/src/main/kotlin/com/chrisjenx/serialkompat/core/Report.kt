package com.chrisjenx.serialkompat.core

/**
 * The outcome of a compatibility check: the classified [findings] plus the
 * [accepted] breaks that sanction some of them. Splits findings into [active]
 * (which count against the gate) and [acknowledged] (sanctioned), and decides
 * whether the gate should fail for a given severity floor.
 */
public data class Report(
    val findings: List<Finding>,
    val accepted: List<AcceptedBreak> = emptyList(),
) {
    /** Findings sanctioned by an [AcceptedBreak] — logged but non-failing. */
    public val acknowledged: List<Finding> get() = findings.filter { it.isAcceptedBy(accepted) }

    /** Findings that still count against the gate. */
    public val active: List<Finding> get() = findings.filterNot { it.isAcceptedBy(accepted) }

    /** Whether any active finding is at or above [floor] (design §7 fail floor). */
    public fun shouldFail(floor: Severity): Boolean = active.any { it.severity.isAtLeast(floor) }
}
