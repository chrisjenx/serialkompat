package com.chrisjenx.serialkompat.core

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
 * name order, then each contract's member deltas in a fixed order, and finally the
 * per-snapshot static-defect scans over the current snapshot — [Change.CoverageGap]
 * for each unanalysable type, then [Change.DiscriminatorCollision] for each
 * unserializable subtype/discriminator clash.
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

            // Which enums, in the *baseline* (old = the forward reader), are read only by defaulted
            // direct properties — the precondition for coerceInputValues to rescue an added value (#129).
            val oldCoercibleEnums = coercibleEnumNames(old)

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
                addAll(diffContract(before, after, oldCoercibleEnums))
            }

            val moved = activeRenames.keys + activeRenames.values
            for (serialName in (oldByName.keys + newByName.keys).sorted()) {
                if (serialName in moved) continue
                val before = oldByName[serialName]
                val after = newByName[serialName]
                when {
                    before == null && after != null -> add(Change.ContractAdded(serialName, after.kind))
                    before != null && after == null -> add(Change.ContractRemoved(serialName, before.kind))
                    before != null && after != null -> addAll(diffContract(before, after, oldCoercibleEnums))
                }
            }

            // Surface every unanalysable contract in the current snapshot as a coverage gap,
            // whether or not it changed — the gate cannot verify it, so it must never pass
            // silently ("unanalysable ≠ safe", design §10). The classifier scores it a WARN.
            new.contracts
                .filter { it.kind == ContractKind.OPAQUE }
                .map { it.serialName }
                .sorted()
                .forEach { add(Change.CoverageGap(it)) }

            addAll(discriminatorCollisions(new))
        }

    /**
     * Sealed/polymorphic subtypes whose property key shadows the base's class
     * discriminator: kotlinx-serialization refuses to encode such a model, so it is
     * flagged on every diff (like a coverage gap), not as a delta between snapshots
     * (design §7, #132). Suppressed when the discriminator is not emitted at all
     * (`classDiscriminatorMode = NONE`), where there is nothing to collide with.
     * Emitted in a deterministic (base, then subtype) order over the already-sorted
     * snapshot.
     */
    private fun discriminatorCollisions(new: Snapshot): List<Change> {
        if (new.config.classDiscriminatorMode == "NONE") return emptyList()
        val byName = new.contracts.associateBy { it.serialName }
        return new.contracts
            .filter { it.kind == ContractKind.SEALED || it.kind == ContractKind.POLYMORPHIC }
            .flatMap { base ->
                val discriminator = base.discriminator ?: return@flatMap emptyList()
                base.subtypes.mapNotNull { subtype ->
                    val subContract = byName[subtype.serialName]
                    if (subContract != null && subContract.elements.any { it.name == discriminator }) {
                        Change.DiscriminatorCollision(base.serialName, discriminator, subtype.serialName)
                    } else {
                        null
                    }
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
        oldCoercibleEnums: Set<String>,
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
            ContractKind.ENUM -> diffEnumValues(after.serialName, before, after, oldCoercibleEnums)
            ContractKind.SEALED, ContractKind.POLYMORPHIC -> diffPolymorphic(after.serialName, before, after)
            ContractKind.OPAQUE -> emptyList() // unanalyzable — no internals to diff
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
        oldCoercibleEnums: Set<String>,
    ): List<Change> =
        buildList {
            val old = before.enumValues.toSet()
            val new = after.enumValues.toSet()
            // An added value is forward-coercible only if every baseline field reading this enum can
            // fall back to a default (recorded per enum); the classifier pairs it with the reader's
            // coerceInputValues setting to decide WARN vs BREAK (#129).
            val coercible = contract in oldCoercibleEnums
            (new - old).sorted().forEach {
                add(
                    Change.EnumValueAdded(contract, it, baselineFieldsCoercible = coercible),
                )
            }
            (old - new).sorted().forEach { add(Change.EnumValueRemoved(contract, it)) }
        }

    /**
     * The enums in [snapshot] every reference to which is a **defaulted direct property** — the only
     * shape `coerceInputValues` can rescue when a value is added (#129). An enum read by a required
     * direct field, by a nested (`List`/`Map`/generic) usage, or only at the top level (no field) is
     * disqualified: those decodes throw on an unknown value regardless of the coerce setting.
     */
    private fun coercibleEnumNames(snapshot: Snapshot): Set<String> {
        val enumNames =
            snapshot.contracts
                .filterTo(
                    mutableSetOf(),
                ) { it.kind == ContractKind.ENUM }
                .map { it.serialName }
        if (enumNames.isEmpty()) return emptySet()
        val hasDefaultedDirect = mutableSetOf<String>()
        val disqualified = mutableSetOf<String>()
        for (contract in snapshot.contracts) {
            for (element in contract.elements) {
                for (enumName in enumNames) {
                    when (enumReference(element.type, enumName)) {
                        EnumRef.DIRECT ->
                            if (element.optional) {
                                hasDefaultedDirect += enumName
                            } else {
                                disqualified +=
                                    enumName
                            }
                        EnumRef.NESTED -> disqualified += enumName
                        EnumRef.NONE -> Unit
                    }
                }
            }
        }
        return hasDefaultedDirect - disqualified
    }

    private enum class EnumRef { DIRECT, NESTED, NONE }

    /** How an element of type [type] references the enum [enumName]: as its whole type, nested in a generic, or not. */
    private fun enumReference(
        type: String,
        enumName: String,
    ): EnumRef =
        when {
            type == enumName -> EnumRef.DIRECT
            // Split on the generic delimiters and strip nullable markers so `List<E>` / `Map<E,V>` /
            // `List<E?>` count as nested, while a distinct type that merely *contains* the name as a
            // substring (e.g. `Enclosing`) does not falsely match.
            type.split('<', '>', ',').any { it.trim().removeSuffix("?") == enumName } -> EnumRef.NESTED
            else -> EnumRef.NONE
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
            // Carry the baseline (old) base's default-deserializer tolerance: it is the reader in the
            // only non-safe direction (forward), so it decides whether an added subtype is a WARN
            // (coerced to the sentinel) or a BREAK (#128).
            (new - old).sortedWith(SUBTYPE_ORDER).forEach {
                add(Change.SubtypeAdded(contract, it, baseHadDefaultDeserializer = before.hasPolymorphicDefault))
            }
            (old - new).sortedWith(SUBTYPE_ORDER).forEach { add(Change.SubtypeRemoved(contract, it)) }
        }

    private val SUBTYPE_ORDER = compareBy<Subtype>({ it.discriminatorValue }, { it.serialName })
}
