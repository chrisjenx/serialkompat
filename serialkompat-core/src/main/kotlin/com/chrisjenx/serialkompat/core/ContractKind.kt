package com.chrisjenx.serialkompat.core

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

    /**
     * A type the extractor could not faithfully analyze (unknown `SerialKind`,
     * unresolved contextual/custom serializer, or an extraction failure). Recorded
     * as an explicit coverage gap rather than crashing or being silently dropped
     * (design §10) — never assumed safe.
     */
    OPAQUE,
}
