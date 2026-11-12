package com.chrisjenx.serialkompat.core

/**
 * Restricts which contracts are checked, by name prefix (design §7, §9).
 * A type is in scope when it matches any [include] prefix and no [exclude] prefix
 * (exclude wins). A prefix matches a contract's serial name, its qualified `Base/sub` name, or its
 * Kotlin class name when known (#249), so a package can be scoped even when its types use custom
 * or short `@SerialName`s. A sealed/polymorphic subtype (#200) also matches through its base's
 * serial or class name. The default scope — empty-string include, no excludes — includes everything.
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

    /** Whether [contract] is in scope, matching on its bare name, its base, its qualified name or its class. */
    public fun contains(contract: Contract): Boolean = contains(contract, emptyMap())

    /**
     * As [contains], also matching class names inferred from [classNames] (qualified or bare serial
     * name → class name), for a contract recorded before class names were.
     */
    internal fun contains(
        contract: Contract,
        classNames: Map<String, String>,
    ): Boolean {
        val names = namesOf(contract, classNames)
        return include.any { prefix -> names.any { it.startsWith(prefix) } } &&
            exclude.none { prefix -> names.any { it.startsWith(prefix) } }
    }

    private fun namesOf(
        contract: Contract,
        classNames: Map<String, String>,
    ): List<String> =
        buildList {
            add(contract.serialName)
            contract.className?.let(::add)
            classNames[contract.qualifiedName]?.let(::add)
            if (contract.base != null) {
                add(contract.qualifiedName)
                classNames[contract.base]?.let(::add)
            } else {
                classNames[contract.serialName]?.let(::add)
            }
        }
}

/**
 * The Kotlin class names this snapshot records, keyed by each contract's qualified name and, when
 * only one class uses it, by its bare serial name — so a snapshot recorded without class names can
 * be scoped by the current one's (#249). A shared bare name is never attributed to one class.
 */
internal fun Snapshot.classNames(): Map<String, String> {
    val known = contracts.filter { it.className != null }
    val byQualified = known.associate { it.qualifiedName to it.className!! }
    val byBare =
        known
            .groupBy { it.serialName }
            .mapNotNull { (name, group) ->
                group.mapTo(mutableSetOf()) { it.className }.singleOrNull()?.let { name to it }
            }.toMap()
    return byBare + byQualified
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
public fun Snapshot.applyScope(scope: Scope): Coverage = applyScope(scope, classNames())

/** As [applyScope], also matching the class names in [classNames] (see [Snapshot.classNames]). */
internal fun Snapshot.applyScope(
    scope: Scope,
    classNames: Map<String, String>,
): Coverage {
    val (kept, dropped) = contracts.partition { scope.contains(it, classNames) }
    return Coverage(Snapshot(kept, config), dropped.map { it.qualifiedName })
}
