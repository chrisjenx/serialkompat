package com.chrisjenx.serialkompat.extractor.scopefixtures

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fixtures for #249: an unshipped `v2` API whose types use custom or short `@SerialName`s, so no
 * serial name starts with the `v2` package — only their Kotlin class names do.
 */
object V2 {
    @Serializable
    @SerialName("PaymentV2")
    data class PaymentV2(
        val amount: Long,
    )

    /** The same wire type, one change later (a type change on `amount`). */
    @Serializable
    @SerialName("PaymentV2")
    data class PaymentV2Next(
        val amount: String,
    )

    @Serializable
    sealed class Method {
        @Serializable
        @SerialName("bank")
        data class Bank(
            val iban: String,
        ) : Method()

        /** A nested sealed class: kotlinx flattens its subtypes into [Method]'s. */
        @Serializable
        @SerialName("card")
        sealed class Card : Method() {
            @Serializable
            @SerialName("visa")
            data class Visa(
                val last4: String,
            ) : Card()
        }
    }
}

object V2Shared {
    /** A v2 root sharing `@SerialName("created")` with the unrelated v1 subtype [V1.OrderEvent.Created]. */
    @Serializable
    @SerialName("created")
    data class Created(
        val at: Long,
    )
}

object V1 {
    @Serializable
    @SerialName("Refund")
    data class Refund(
        val id: String,
    )

    @Serializable
    sealed class OrderEvent {
        /** Not a root of its own: reached only through [OrderEvent]. */
        @Serializable
        @SerialName("created")
        data class Created(
            val id: String,
        ) : OrderEvent()
    }
}
