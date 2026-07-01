package io.github.chrisjenx.serialkompat.core

/**
 * Computes the structural [Change]s between two [Snapshot]s. This is the pure
 * heart of the engine (design §3): it reads two snapshots, never knowing where
 * they came from (git ref, file, live), and emits direction-neutral deltas for
 * the classifier to judge.
 *
 * Identity is by serial name for contracts and by key for elements, so field
 * reordering yields no change (the [Snapshot] model already normalizes order).
 * A declared [renames] mapping lets a moved type be followed as a [Change.ContractMoved]
 * rather than a spurious remove + add (design §8).
 *
 * Output order is deterministic: config changes first, then contracts in serial
 * name order, then each contract's member deltas in a fixed order.
 */
public object SnapshotDiffer {
    /**
     * Diffs [old] (baseline) against [new] (current), oldest→newest.
     *
     * [renames] declares intentional serial-name changes (old → new) so a moved
     * type is followed and its contents diffed, rather than reported as a
     * spurious remove + add (design §8). Only renames whose both endpoints are
     * present are honored, so a stale entry can never silently drop a contract.
     */
    public fun diff(
        old: Snapshot,
        new: Snapshot,
        renames: Map<String, String> = emptyMap(),
    ): List<Change> =
        buildList {
            addAll(diffConfig(old.config, new.config))

            val oldByName = old.contracts.associateBy { it.serialName }
            val newByName = new.contracts.associateBy { it.serialName }

            // Honour a rename only for a genuine move: the source must be gone from `new` and
            // the target new to `old`. Otherwise both endpoints are still present, and treating
            // it as a move would silently drop the diff of a contract that is still on the wire.
            val activeRenames =
                renames
                    .filterKeys { it in oldByName && it !in newByName }
                    .filterValues { it in newByName && it !in oldByName }
            for ((oldName, newName) in activeRenames.entries.sortedBy { it.key }) {
                val before = oldByName.getValue(oldName)
                val after = newByName.getValue(newName)
                add(Change.ContractMoved(oldName, newName, after.kind))
                addAll(diffContract(before, after))
            }

            val moved = activeRenames.keys + activeRenames.values
            for (serialName in (oldByName.keys + newByName.keys).sorted()) {
                if (serialName in moved) continue
                val before = oldByName[serialName]
                val after = newByName[serialName]
                when {
                    before == null && after != null -> add(Change.ContractAdded(serialName, after.kind))
                    before != null && after == null -> add(Change.ContractRemoved(serialName, before.kind))
                    before != null && after != null -> addAll(diffContract(before, after))
                }
            }
        }

    private fun diffConfig(
        old: SnapshotConfig,
        new: SnapshotConfig,
    ): List<Change> {
        val fields =
            listOf<Triple<String, Any, Any>>(
                Triple("classDiscriminator", old.classDiscriminator, new.classDiscriminator),
                Triple("classDiscriminatorMode", old.classDiscriminatorMode, new.classDiscriminatorMode),
                Triple("coerceInputValues", old.coerceInputValues, new.coerceInputValues),
                Triple("encodeDefaults", old.encodeDefaults, new.encodeDefaults),
                Triple("explicitNulls", old.explicitNulls, new.explicitNulls),
                Triple("ignoreUnknownKeys", old.ignoreUnknownKeys, new.ignoreUnknownKeys),
                Triple("namingStrategy", old.namingStrategy, new.namingStrategy),
                Triple("useAlternativeNames", old.useAlternativeNames, new.useAlternativeNames),
            )
        return fields
            .filter { (_, before, after) -> before != after }
            .map { (name, before, after) -> Change.ConfigChanged(name, before.toString(), after.toString()) }
    }

    private fun diffContract(
        before: Contract,
        after: Contract,
    ): List<Change> {
        // A change of kind (e.g. CLASS → ENUM) is a different type on the wire;
        // surface it as remove + add rather than a fabricated member diff.
        if (before.kind != after.kind) {
            return listOf(
                Change.ContractRemoved(before.serialName, before.kind),
                Change.ContractAdded(after.serialName, after.kind),
            )
        }
        return when (after.kind) {
            ContractKind.CLASS, ContractKind.OBJECT -> diffElements(after.serialName, before, after)
            ContractKind.ENUM -> diffEnumValues(after.serialName, before, after)
            ContractKind.SEALED, ContractKind.POLYMORPHIC -> diffPolymorphic(after.serialName, before, after)
        }
    }

    private fun diffElements(
        contract: String,
        before: Contract,
        after: Contract,
    ): List<Change> =
        buildList {
            val oldByName = before.elements.associateBy { it.name }
            val newByName = after.elements.associateBy { it.name }
            for (name in (oldByName.keys + newByName.keys).sorted()) {
                val old = oldByName[name]
                val new = newByName[name]
                when {
                    old == null && new != null -> add(Change.ElementAdded(contract, new))
                    old != null && new == null -> add(Change.ElementRemoved(contract, old))
                    old != null && new != null -> {
                        if (old.type != new.type) {
                            add(Change.ElementTypeChanged(contract, name, old.type, new.type))
                        }
                        if (old.optional != new.optional) {
                            add(Change.ElementOptionalityChanged(contract, name, old.optional, new.optional))
                        }
                        if (old.nullable != new.nullable) {
                            add(Change.ElementNullabilityChanged(contract, name, old.nullable, new.nullable))
                        }
                        if (old.jsonNames != new.jsonNames) {
                            add(Change.ElementJsonNamesChanged(contract, name, old.jsonNames, new.jsonNames))
                        }
                    }
                }
            }
        }

    private fun diffEnumValues(
        contract: String,
        before: Contract,
        after: Contract,
    ): List<Change> =
        buildList {
            val old = before.enumValues.toSet()
            val new = after.enumValues.toSet()
            (new - old).sorted().forEach { add(Change.EnumValueAdded(contract, it)) }
            (old - new).sorted().forEach { add(Change.EnumValueRemoved(contract, it)) }
        }

    private fun diffPolymorphic(
        contract: String,
        before: Contract,
        after: Contract,
    ): List<Change> =
        buildList {
            if (before.discriminator != after.discriminator) {
                add(Change.DiscriminatorChanged(contract, before.discriminator, after.discriminator))
            }
            val old = before.subtypes.toSet()
            val new = after.subtypes.toSet()
            (new - old).sortedWith(SUBTYPE_ORDER).forEach { add(Change.SubtypeAdded(contract, it)) }
            (old - new).sortedWith(SUBTYPE_ORDER).forEach { add(Change.SubtypeRemoved(contract, it)) }
        }

    private val SUBTYPE_ORDER = compareBy<Subtype>({ it.discriminatorValue }, { it.serialName })
}
