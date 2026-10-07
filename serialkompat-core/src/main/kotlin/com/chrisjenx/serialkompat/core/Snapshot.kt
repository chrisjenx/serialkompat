package com.chrisjenx.serialkompat.core

/**
 * The canonical, comparable representation of a JSON wire contract: the set of
 * serializable [contracts] plus the relevant [config].
 *
 * [contracts] are normalized to sorted order (by serial name) so that equality
 * and the canonical text form are independent of extraction order. This is the
 * one swappable artifact the whole tool hangs off — extraction produces it, the
 * differ consumes two of them, and it never knows where it came from (design §3).
 */
public class Snapshot(
    contracts: List<Contract> = emptyList(),
    public val config: SnapshotConfig = SnapshotConfig(),
) {
    /** Contracts, sorted by serial name, then [Contract.base] (unqualified first). */
    public val contracts: List<Contract> =
        contracts.sortedWith(compareBy<Contract> { it.serialName }.thenBy(nullsFirst()) { it.base })

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Snapshot) return false
        return contracts == other.contracts && config == other.config
    }

    override fun hashCode(): Int = 31 * contracts.hashCode() + config.hashCode()

    override fun toString(): String = "Snapshot(contracts=$contracts, config=$config)"
}
