package io.github.chrisjenx.serialkompat.core

/**
 * The structural kind of a [Contract], mirroring the subset of
 * kotlinx-serialization's `SerialKind` taxonomy that matters for wire
 * compatibility.
 */
public enum class ContractKind {
    /** A regular `@Serializable` class or data class. */
    CLASS,

    /** A serializable `object` (singleton; no elements on the wire). */
    OBJECT,

    /** An `enum class`; carries a set of value names. */
    ENUM,

    /** A `sealed` hierarchy; carries a discriminator and a closed subtype map. */
    SEALED,

    /** An open polymorphic hierarchy resolved via a `SerializersModule`. */
    POLYMORPHIC,
}
