package com.chrisjenx.serialkompat.core

/**
 * One entry in a [ContractKind.SEALED] or [ContractKind.POLYMORPHIC] contract's
 * subtype map: the discriminator value written to the wire, and the serial name
 * of the concrete subtype it selects.
 */
public data class Subtype(
    /** The discriminator value on the wire (e.g. `"card"`). */
    public val discriminatorValue: String,
    /** The serial name of the concrete subtype contract. */
    public val serialName: String,
)
