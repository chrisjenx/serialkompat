package com.chrisjenx.serialkompat.core

/**
 * The wire contract of a single `@Serializable` type, identified by its
 * [serialName] and, for a sealed/polymorphic subtype, the [base] it was reached
 * through (#200). Collections are normalized to sorted order so that equality and
 * the canonical text form are independent of declaration order.
 *
 * Which payload is populated depends on [kind]:
 * - [ContractKind.CLASS] / [ContractKind.OBJECT] → [elements]
 * - [ContractKind.ENUM] → [enumValues]
 * - [ContractKind.SEALED] / [ContractKind.POLYMORPHIC] → [discriminator] + [subtypes]
 *   (plus [hasPolymorphicDefault] for an open [ContractKind.POLYMORPHIC] base)
 */
public class Contract(
    public val serialName: String,
    public val kind: ContractKind,
    elements: List<Element> = emptyList(),
    enumValues: List<String> = emptyList(),
    public val discriminator: String? = null,
    subtypes: List<Subtype> = emptyList(),
    /**
     * Whether an open [ContractKind.POLYMORPHIC] base registered a default
     * deserializer in its `SerializersModule`. That fallback lets a reader coerce an
     * unknown (e.g. newly-added) subtype's discriminator to a sentinel instead of
     * throwing, which downgrades an added-subtype forward break to a WARN (#128).
     * Always `false` for closed/sealed and non-polymorphic contracts.
     */
    public val hasPolymorphicDefault: Boolean = false,
    /**
     * The serial name of the [ContractKind.SEALED] / [ContractKind.POLYMORPHIC] contract this
     * contract was reached through as a subtype, or `null` for a contract reached any other way.
     * A subtype's serial name is only unique within its base, so `(serialName, base)` is the
     * contract's identity (#200). A subtype of two bases is recorded once per base.
     */
    public val base: String? = null,
) {
    /**
     * The contract's display identity: `Base/sub` for a subtype, else the bare [serialName].
     * Used in findings and accepted by `renames`, accepted breaks, and [Scope].
     */
    public val qualifiedName: String get() = qualify(serialName, base)

    /** Class elements, sorted by name. */
    public val elements: List<Element> = elements.sortedBy { it.name }

    /** Enum value names, sorted. */
    public val enumValues: List<String> = enumValues.sorted()

    /** Sealed/polymorphic subtypes, sorted by discriminator value. */
    public val subtypes: List<Subtype> = subtypes.sortedBy { it.discriminatorValue }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contract) return false
        return serialName == other.serialName &&
            kind == other.kind &&
            elements == other.elements &&
            enumValues == other.enumValues &&
            discriminator == other.discriminator &&
            subtypes == other.subtypes &&
            hasPolymorphicDefault == other.hasPolymorphicDefault &&
            base == other.base
    }

    override fun hashCode(): Int {
        var result = serialName.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + elements.hashCode()
        result = 31 * result + enumValues.hashCode()
        result = 31 * result + (discriminator?.hashCode() ?: 0)
        result = 31 * result + subtypes.hashCode()
        result = 31 * result + hasPolymorphicDefault.hashCode()
        result = 31 * result + (base?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "Contract(serialName=$serialName, kind=$kind, elements=$elements, " +
            "enumValues=$enumValues, discriminator=$discriminator, subtypes=$subtypes, " +
            "hasPolymorphicDefault=$hasPolymorphicDefault, base=$base)"
}

/** The display form of a `(serialName, base)` identity: `base/serialName`, or the bare name. */
internal fun qualify(
    serialName: String,
    base: String?,
): String = if (base == null) serialName else "$base/$serialName"

/**
 * Whether a user-written contract reference [token] names `(serialName, base)`: either exactly
 * its qualified form `Base/sub`, or its bare serial name (any base).
 */
internal fun namesContract(
    token: String,
    serialName: String,
    base: String?,
): Boolean = token == serialName || token == qualify(serialName, base)
