package com.example

import com.chrisjenx.serialkompat.annotations.SerialkompatChecked
import kotlinx.serialization.Serializable

// Embedded on docs/adopting.md ("Start with nothing checked").
// --8<-- [start:order-event]
@Serializable
@SerialkompatChecked
data class OrderEvent(
    val id: String,
)
// --8<-- [end:order-event]
