package com.chrisjenx.serialkompat.core

/**
 * Turns structural [Change]s into severity-ranked [Finding]s by applying the
 * rule matrix (design §7) under a [CompatibilityProfile] and the reader/writer
 * [SnapshotConfig]s.
 *
 * Compatibility is a function of `(change, direction, reader/writer config)`, so
 * the same change can be safe one way and breaking the other:
 * - **backward** = new code reads old data -> the *reader* is the new config,
 *   the *writer* is the old config;
 * - **forward** = old code reads new data -> the *reader* is the old config,
 *   the *writer* is the new config.
 *
 * Only actionable findings are reported: a direction that is [Severity.SAFE]
 * yields no finding. Config *changes* are themselves classified (design §6): a
 * naming-strategy or discriminator change is a BREAK, a tightened reader a WARN.
 */
public class Classifier(
    private val profile: CompatibilityProfile = CompatibilityProfile(),
) {
    /**
     * Classifies [changes] given the [oldConfig] (baseline) and [newConfig]
     * (current) wire configurations.
     */
    public fun classify(
        changes: List<Change>,
        oldConfig: SnapshotConfig = SnapshotConfig(),
        newConfig: SnapshotConfig = SnapshotConfig(),
    ): List<Finding> =
        changes.flatMap { change ->
            val verdict = verdictFor(change, oldConfig, newConfig) ?: return@flatMap emptyList()
            buildList {
                if (profile.direction.checksBackward && verdict.backward.isAtLeast(Severity.WARN)) {
                    add(verdict.toFinding(CompatibilityDirection.BACKWARD, verdict.backward, change))
                }
                if (profile.direction.checksForward && verdict.forward.isAtLeast(Severity.WARN)) {
                    add(verdict.toFinding(CompatibilityDirection.FORWARD, verdict.forward, change))
                }
            }
        }

    private fun readerTolerant(readerConfig: SnapshotConfig): Boolean =
        when (profile.readerTolerance) {
            ReaderTolerance.STRICT -> false
            ReaderTolerance.FROM_CONFIG -> readerConfig.ignoreUnknownKeys
        }

    private fun verdictFor(
        change: Change,
        oldConfig: SnapshotConfig,
        newConfig: SnapshotConfig,
    ): Verdict? =
        when (change) {
            // Adding a whole type is safe both ways.
            is Change.ContractAdded -> null

            is Change.ContractRemoved ->
                Verdict(
                    Rules.CONTRACT_REMOVED,
                    change.serialName,
                    "type ${change.serialName}",
                    backward = Severity.BREAK,
                    forward = Severity.BREAK,
                    message = "type ${change.serialName} was removed",
                    fixHint = "Keep the type while data or peers still use it; bump major to remove.",
                )

            is Change.ElementAdded ->
                Verdict(
                    Rules.PROPERTY_ADDED,
                    change.contract,
                    "field '${change.element.name}'",
                    // backward: break unless optional
                    backward = if (change.element.optional) Severity.SAFE else Severity.BREAK,
                    // forward: old reader meets an unknown key - fine only if it tolerates them.
                    forward = if (readerTolerant(oldConfig)) Severity.SAFE else Severity.BREAK,
                    message = "field '${change.element.name}' was added to ${change.contract}",
                    fixHint = "Give new fields a default; readers should set ignoreUnknownKeys; else bump major.",
                )

            is Change.ElementRemoved ->
                Verdict(
                    Rules.PROPERTY_REMOVED,
                    change.contract,
                    "field '${change.element.name}'",
                    // backward: new reader meets the now-unknown old key. A strict reader throws (BREAK);
                    // a tolerant reader decodes but SILENTLY DROPS the field's value — a silent-data-loss
                    // WARN, never SAFE (design §7: "silent data-loss = WARN"). This is also what surfaces a
                    // field rename, which the differ decomposes into remove + add.
                    backward = if (readerTolerant(newConfig)) Severity.WARN else Severity.BREAK,
                    // forward: old reader misses the field - fine only if it was optional for it.
                    forward = if (change.element.optional) Severity.SAFE else Severity.BREAK,
                    message = "field '${change.element.name}' was removed from ${change.contract}",
                    fixHint =
                        "Removing a field drops its data for tolerant readers; keep it (or bridge a " +
                            "rename with @JsonNames) until nothing uses it; else bump major.",
                )

            is Change.ElementOptionalityChanged ->
                if (change.wasOptional && !change.nowOptional) {
                    Verdict(
                        Rules.PROPERTY_OPTIONALITY,
                        change.contract,
                        "field '${change.element}'",
                        backward = Severity.BREAK, // old payloads may omit it, new reader now requires it
                        forward = Severity.SAFE,
                        message = "field '${change.element}' became required in ${change.contract}",
                        fixHint = "Keep a default so older payloads still decode.",
                    )
                } else {
                    Verdict(
                        Rules.PROPERTY_OPTIONALITY,
                        change.contract,
                        "field '${change.element}'",
                        backward = Severity.SAFE,
                        // forward: new writer may omit it unless it always encodes defaults.
                        forward = if (newConfig.encodeDefaults) Severity.SAFE else Severity.BREAK,
                        message = "field '${change.element}' became optional in ${change.contract}",
                        fixHint = "Set encodeDefaults=true on the writer, or keep the field required.",
                    )
                }

            is Change.ElementNullabilityChanged ->
                if (!change.wasNullable && change.nowNullable) {
                    Verdict(
                        Rules.PROPERTY_NULLABILITY,
                        change.contract,
                        "field '${change.element}'",
                        backward = Severity.SAFE,
                        // forward: an emitted null breaks an old non-null reader. If the writer omits
                        // nulls (explicitNulls=false) the key is simply absent, so whether it breaks
                        // depends on that field's optionality (not visible here) — conditional → WARN.
                        forward = if (newConfig.explicitNulls) Severity.BREAK else Severity.WARN,
                        message = "field '${change.element}' became nullable in ${change.contract}",
                        fixHint = "Old readers can't accept null here; introduce a new field or bump major.",
                    )
                } else {
                    Verdict(
                        Rules.PROPERTY_NULLABILITY,
                        change.contract,
                        "field '${change.element}'",
                        backward = Severity.BREAK, // old null values can't decode into the new non-null type
                        forward = Severity.SAFE,
                        message = "field '${change.element}' became non-null in ${change.contract}",
                        fixHint = "Old data may contain null here; keep it nullable or bump major.",
                    )
                }

            is Change.ElementTypeChanged ->
                Verdict(
                    Rules.PROPERTY_TYPE_CHANGED,
                    change.contract,
                    "field '${change.element}'",
                    // A numeric widening reads old->new fine, but old readers can't read the wider value.
                    backward = if (isWidening(change.oldType, change.newType)) Severity.SAFE else Severity.BREAK,
                    forward = Severity.BREAK,
                    message = "field '${change.element}' type changed ${change.oldType} -> ${change.newType}",
                    fixHint = "Introduce a new field instead of changing a type; bump major.",
                )

            is Change.EnumValueAdded ->
                Verdict(
                    Rules.ENUM_VALUE_ADDED,
                    change.contract,
                    "value '${change.value}'",
                    backward = Severity.SAFE,
                    // forward: old reader meets an unknown value. coerceInputValues only rescues it
                    // when the *field* also has a default — invisible from this change — so a coercing
                    // reader is conditionally-safe (WARN), not proven safe; a strict reader BREAKs.
                    forward = if (oldConfig.coerceInputValues) Severity.WARN else Severity.BREAK,
                    message = "enum value '${change.value}' was added to ${change.contract}",
                    fixHint = "Enable coerceInputValues *and* give the field a default on readers, or bump major.",
                )

            is Change.EnumValueRemoved ->
                Verdict(
                    Rules.ENUM_VALUE_REMOVED,
                    change.contract,
                    "value '${change.value}'",
                    backward = Severity.BREAK, // old data may carry the removed value
                    forward = Severity.SAFE,
                    message = "enum value '${change.value}' was removed from ${change.contract}",
                    fixHint = "Keep the value (deprecated) until no persisted/old data uses it.",
                )

            is Change.SubtypeAdded ->
                Verdict(
                    Rules.SUBTYPE_ADDED,
                    change.contract,
                    "subtype '${change.subtype.serialName}'",
                    backward = Severity.SAFE,
                    forward = Severity.BREAK, // old reader can't resolve the new subtype
                    message = "subtype '${change.subtype.serialName}' was added to ${change.contract}",
                    fixHint = "Register a default deserializer on old readers, or bump major.",
                )

            is Change.SubtypeRemoved ->
                Verdict(
                    Rules.SUBTYPE_REMOVED,
                    change.contract,
                    "subtype '${change.subtype.serialName}'",
                    backward = Severity.BREAK, // old data may carry the removed subtype
                    forward = Severity.SAFE,
                    message = "subtype '${change.subtype.serialName}' was removed from ${change.contract}",
                    fixHint = "Keep the subtype until no old/persisted data uses it.",
                )

            is Change.DiscriminatorChanged ->
                Verdict(
                    Rules.DISCRIMINATOR_CHANGED,
                    change.contract,
                    "discriminator",
                    backward = Severity.BREAK,
                    forward = Severity.BREAK,
                    message = "discriminator changed: ${change.oldDiscriminator} -> ${change.newDiscriminator}",
                    fixHint = "Don't change the discriminator key; it breaks all polymorphic decoding.",
                )

            is Change.ElementJsonNamesChanged -> {
                // Aliases only widen the keys a reader accepts. Adding one is safe; dropping one
                // narrows acceptance, so a peer still sending that key (heterogeneous producers,
                // per the threat model) can break the new reader — backward, and conditional → WARN.
                val dropped = change.oldAliases - change.newAliases.toSet()
                if (dropped.isEmpty()) {
                    null
                } else {
                    Verdict(
                        Rules.PROPERTY_JSON_NAMES,
                        change.contract,
                        "field '${change.element}'",
                        backward = Severity.WARN,
                        forward = Severity.SAFE,
                        message =
                            "field '${change.element}' dropped JSON alias(es) " +
                                "${dropped.joinToString()} in ${change.contract}",
                        fixHint = "Keep @JsonNames aliases while any producer may still send those keys.",
                    )
                }
            }

            // Moving a plain type is wire-neutral (its class name isn't on the
            // wire); moving a polymorphic type changes the discriminator value.
            is Change.ContractMoved ->
                if (change.kind == ContractKind.SEALED || change.kind == ContractKind.POLYMORPHIC) {
                    Verdict(
                        Rules.DISCRIMINATOR_VALUE_CHANGED,
                        change.newSerialName,
                        "type ${change.oldSerialName} -> ${change.newSerialName}",
                        backward = Severity.BREAK,
                        forward = Severity.BREAK,
                        message = "polymorphic type moved ${change.oldSerialName} -> ${change.newSerialName}",
                        fixHint = "Pin the old value with @SerialName, or file an exception; the move is a wire break.",
                    )
                } else {
                    null // plain move is safe
                }

            is Change.ConfigChanged -> configVerdict(change)

            // A subtype property that shadows the class discriminator makes the model
            // unserializable — the real library refuses to encode it — so it breaks both
            // directions regardless of config (design §7, #132).
            is Change.DiscriminatorCollision ->
                Verdict(
                    Rules.DISCRIMINATOR_COLLISION,
                    change.contract,
                    "subtype '${change.subtype}'",
                    backward = Severity.BREAK,
                    forward = Severity.BREAK,
                    message =
                        "subtype '${change.subtype}' has a property named '${change.discriminator}' that " +
                            "collides with ${change.contract}'s class discriminator — the model cannot be serialized",
                    fixHint =
                        "Rename the colliding property, or set a different @JsonClassDiscriminator on ${change.contract}.",
                )

            // An unanalysable type can't be verified either way — surfaced as a WARN
            // coverage gap so it is never silently assumed compatible (design §10).
            is Change.CoverageGap ->
                Verdict(
                    Rules.COVERAGE_GAP,
                    change.serialName,
                    "type ${change.serialName}",
                    backward = Severity.WARN,
                    forward = Severity.WARN,
                    message = "type ${change.serialName} is opaque (unanalysable) — the gate cannot verify it",
                    fixHint = "Provide an analysable @Serializable form, or accept this coverage gap explicitly.",
                )
        }

    /**
     * Classifies a change to a wire-relevant `Json` setting (design §6). Several
     * settings are inherently one-directional: a reader-side flag only affects the
     * direction whose reader changed (backward = new reader ← old data), a
     * writer-side flag only the other (forward = old reader ← new data), so
     * severities are assigned per direction rather than symmetrically.
     */
    private fun configVerdict(change: Change.ConfigChanged): Verdict? {
        val disabled = change.oldValue == "true" && change.newValue == "false"
        val spec =
            when (change.field) {
                // Rename every key / break polymorphic decoding — both directions.
                "namingStrategy" ->
                    ConfigSpec(
                        Rules.CONFIG_NAMING_STRATEGY,
                        Severity.BREAK,
                        Severity.BREAK,
                        "A naming-strategy change renames every key; keep it stable or bump major.",
                    )
                "classDiscriminator" ->
                    ConfigSpec(
                        Rules.CONFIG_DISCRIMINATOR,
                        Severity.BREAK,
                        Severity.BREAK,
                        "Changing the discriminator breaks all polymorphic decoding.",
                    )
                "classDiscriminatorMode" ->
                    ConfigSpec(
                        Rules.CONFIG_DISCRIMINATOR,
                        Severity.BREAK,
                        Severity.BREAK,
                        "Changing whether the discriminator is emitted breaks polymorphic decoding.",
                    )
                // Reader-side (backward only): a stricter NEW reader can reject old data.
                "ignoreUnknownKeys" ->
                    ConfigSpec(
                        Rules.CONFIG_READER_STRICTNESS,
                        if (disabled) Severity.WARN else Severity.SAFE,
                        Severity.SAFE,
                        "A stricter reader now rejects previously-tolerated unknown keys.",
                    )
                "coerceInputValues" ->
                    ConfigSpec(
                        Rules.CONFIG_COERCE_INPUT,
                        if (disabled) Severity.WARN else Severity.SAFE,
                        Severity.SAFE,
                        "A reader that no longer coerces invalid values may fail to decode.",
                    )
                "useAlternativeNames" ->
                    ConfigSpec(
                        Rules.CONFIG_READER_STRICTNESS,
                        if (disabled) Severity.WARN else Severity.SAFE,
                        Severity.SAFE,
                        "The reader no longer accepts @JsonNames alias keys.",
                    )
                // Writer-side (forward only): the NEW writer may omit fields the old reader expects.
                "encodeDefaults" ->
                    ConfigSpec(
                        Rules.CONFIG_ENCODE_DEFAULTS,
                        Severity.SAFE,
                        if (disabled) Severity.WARN else Severity.SAFE,
                        "No longer writing defaults can drop fields peers rely on.",
                    )
                // Whether nulls are written; conditional in both directions — kept coarse (WARN).
                "explicitNulls" ->
                    ConfigSpec(
                        Rules.CONFIG_EXPLICIT_NULLS,
                        Severity.WARN,
                        Severity.WARN,
                        "This changes whether nulls appear on the wire.",
                    )
                else ->
                    ConfigSpec(
                        Rules.CONFIG_CHANGED,
                        Severity.WARN,
                        Severity.WARN,
                        "A wire-relevant Json setting changed.",
                    )
            }
        if (spec.backward == Severity.SAFE && spec.forward == Severity.SAFE) return null
        return Verdict(
            spec.rule,
            "Json config",
            "config '${change.field}' ${change.oldValue} -> ${change.newValue}",
            backward = spec.backward,
            forward = spec.forward,
            message = "Json ${change.field} changed ${change.oldValue} -> ${change.newValue}",
            fixHint = spec.hint,
        )
    }

    private data class ConfigSpec(
        val rule: String,
        val backward: Severity,
        val forward: Severity,
        val hint: String,
    )

    private fun isWidening(
        oldType: String,
        newType: String,
    ): Boolean = (oldType to newType) in NUMERIC_WIDENINGS

    private class Verdict(
        val rule: String,
        val contract: String,
        val detail: String,
        val backward: Severity,
        val forward: Severity,
        val message: String,
        val fixHint: String?,
    ) {
        fun toFinding(
            direction: CompatibilityDirection,
            severity: Severity,
            change: Change,
        ): Finding = Finding(rule, direction, severity, contract, detail, message, fixHint, change)
    }

    private companion object {
        /** Old->new primitive type refs that a wider reader accepts (backward-safe). */
        private val NUMERIC_WIDENINGS =
            setOf(
                "kotlin.Byte" to "kotlin.Short",
                "kotlin.Byte" to "kotlin.Int",
                "kotlin.Byte" to "kotlin.Long",
                "kotlin.Short" to "kotlin.Int",
                "kotlin.Short" to "kotlin.Long",
                "kotlin.Int" to "kotlin.Long",
                "kotlin.Float" to "kotlin.Double",
            )
    }
}
