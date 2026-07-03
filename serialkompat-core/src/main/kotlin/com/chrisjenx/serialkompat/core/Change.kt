package com.chrisjenx.serialkompat.core

/**
 * A single structural, direction-neutral delta between two [Snapshot]s, as
 * produced by [SnapshotDiffer]. A [Change] states *what* differs, never whether
 * it is safe or breaking — that judgment (rule name + [Severity] + direction) is
 * the classifier's job (design §7). Keeping changes neutral lets one differ feed
 * backward, forward, and config-aware classification without re-diffing.
 */
public sealed interface Change {
    /** A `@Serializable` type present in the new snapshot but not the old. */
    public data class ContractAdded(
        val serialName: String,
        val kind: ContractKind,
    ) : Change

    /** A `@Serializable` type present in the old snapshot but not the new. */
    public data class ContractRemoved(
        val serialName: String,
        val kind: ContractKind,
    ) : Change

    /**
     * A type whose serial name changed via a declared rename/move (design §8):
     * wire-neutral for a plain type, a discriminator break for a polymorphic one.
     */
    public data class ContractMoved(
        val oldSerialName: String,
        val newSerialName: String,
        val kind: ContractKind,
    ) : Change

    /** A new element (field) on an existing contract. */
    public data class ElementAdded(
        val contract: String,
        val element: Element,
    ) : Change

    /** An element removed from an existing contract. */
    public data class ElementRemoved(
        val contract: String,
        val element: Element,
    ) : Change

    /** An existing element's type reference changed. */
    public data class ElementTypeChanged(
        val contract: String,
        val element: String,
        val oldType: String,
        val newType: String,
    ) : Change

    /** An existing element became optional or required. */
    public data class ElementOptionalityChanged(
        val contract: String,
        val element: String,
        val wasOptional: Boolean,
        val nowOptional: Boolean,
    ) : Change

    /** An existing element's nullability changed. */
    public data class ElementNullabilityChanged(
        val contract: String,
        val element: String,
        val wasNullable: Boolean,
        val nowNullable: Boolean,
    ) : Change

    /**
     * An existing element's `@JsonNames` alias set changed. Aliases are a wire
     * fact (they widen what keys a reader accepts) and are §7's mitigation for a
     * key rename, so adding/removing one must not be silently dropped.
     */
    public data class ElementJsonNamesChanged(
        val contract: String,
        val element: String,
        val oldAliases: List<String>,
        val newAliases: List<String>,
    ) : Change

    /** An enum value added to an existing enum contract. */
    public data class EnumValueAdded(
        val contract: String,
        val value: String,
        /**
         * Whether, in the baseline (old) snapshot, *every* field that decodes this enum is a
         * defaulted direct property — the precondition for `coerceInputValues` to rescue an unknown
         * value in the forward direction (old code reading new data). `coerceInputValues` only
         * coerces an unknown enum to a field's default, so a required field, a nested (`List`/`Map`)
         * usage, or a top-level decode still throws. The classifier downgrades the forward BREAK to a
         * WARN only when this is `true` *and* the reader coerces; otherwise it stays a BREAK (#129).
         */
        val baselineFieldsCoercible: Boolean = false,
    ) : Change

    /** An enum value removed from an existing enum contract. */
    public data class EnumValueRemoved(
        val contract: String,
        val value: String,
    ) : Change

    /** A subtype added to an existing sealed/polymorphic contract. */
    public data class SubtypeAdded(
        val contract: String,
        val subtype: Subtype,
        /**
         * Whether the base contract in the baseline (old) snapshot registered a
         * polymorphic default deserializer. That tolerance lets a forward reader
         * (old code reading new data) coerce this newly-added subtype's unknown
         * discriminator to the sentinel instead of throwing (#128), so the classifier
         * uses it to downgrade the forward BREAK to a WARN — silent sentinel
         * substitution, not a clean pass.
         */
        val baseHadDefaultDeserializer: Boolean = false,
    ) : Change

    /** A subtype removed from an existing sealed/polymorphic contract. */
    public data class SubtypeRemoved(
        val contract: String,
        val subtype: Subtype,
    ) : Change

    /** The polymorphic discriminator key of an existing contract changed. */
    public data class DiscriminatorChanged(
        val contract: String,
        val oldDiscriminator: String?,
        val newDiscriminator: String?,
    ) : Change

    /** A `Json` configuration field that affects the wire contract changed. */
    public data class ConfigChanged(
        val field: String,
        val oldValue: String,
        val newValue: String,
    ) : Change

    /**
     * An unanalysable (`OPAQUE`) contract in the current snapshot: the extractor
     * could not derive its wire shape (e.g. a custom serializer), so the gate
     * cannot verify it. Surfaced on every diff so an unanalysable type is never
     * silently treated as compatible ("unanalysable ≠ safe", design §10).
     */
    public data class CoverageGap(
        val serialName: String,
    ) : Change

    /**
     * A sealed/polymorphic [contract] whose class [discriminator] key collides with
     * a property of its [subtype] of the same name. Such a model is **unserializable**
     * — kotlinx-serialization refuses to encode it — so, like a [CoverageGap], it is a
     * defect of a single snapshot surfaced on every diff (not a delta between two),
     * letting the gate catch it statically before the first encode throws (#132).
     */
    public data class DiscriminatorCollision(
        val contract: String,
        val discriminator: String,
        val subtype: String,
    ) : Change
}
