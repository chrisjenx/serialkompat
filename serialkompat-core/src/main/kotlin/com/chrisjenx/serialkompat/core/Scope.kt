package com.chrisjenx.serialkompat.core

/**
 * Restricts which contracts are checked, by serial-name prefix (design §7, §9).
 * A type is in scope when it matches any [include] prefix and no [exclude] prefix
 * (exclude wins). A sealed/polymorphic subtype (#200) matches an entry that prefixes
 * its bare serial name, its base, or its qualified `Base/sub` name. The default scope — empty-string include, no excludes —
 * includes everything.
 *
 * Scope is how a module/package that never crosses the wire is left out of the
 * gate; the exclusion is always explicit config, and [applyScope] makes the
 * excluded set enumerable, so nothing is ever silently dropped from analysis.
 */
public data class Scope(
    val include: List<String> = listOf(""),
    val exclude: List<String> = emptyList(),
) {
    /** Whether a contract with this [serialName] is in scope. */
    public fun contains(serialName: String): Boolean =
        include.any(serialName::startsWith) && exclude.none(serialName::startsWith)

    /** Whether [contract] is in scope, matching on its bare name, its base, or its qualified name. */
    public fun contains(contract: Contract): Boolean =
        include.any { matches(contract, it) } && exclude.none { matches(contract, it) }

    private fun matches(
        contract: Contract,
        prefix: String,
    ): Boolean =
        contract.serialName.startsWith(prefix) ||
            (contract.base != null && contract.qualifiedName.startsWith(prefix))
}

/**
 * The result of applying a [Scope]: the [inScope] snapshot that will be checked,
 * and the qualified names ([Contract.qualifiedName]) [excluded] from checking
 * (surfaced, never silently lost).
 */
public data class Coverage(
    val inScope: Snapshot,
    val excluded: List<String>,
)

/** Partitions this snapshot's contracts by [scope] into checked vs excluded. */
public fun Snapshot.applyScope(scope: Scope): Coverage {
    val (kept, dropped) = contracts.partition { scope.contains(it) }
    return Coverage(Snapshot(kept, config), dropped.map { it.qualifiedName })
}
