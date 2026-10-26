package com.example

// Embedded on docs/got-a-break.md ("Declare a rename"): a JSON property rename bridged with @JsonNames.
// --8<-- [start:order]
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Order(
    @JsonNames("note") val memo: String? = null,
)
// --8<-- [end:order]
