package com.example

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

// The `cborInstance` target the docs name as `com.example.WireCbor.instance` (docs/cbor.md).
// --8<-- [start:wire-cbor]
@OptIn(ExperimentalSerializationApi::class)
object WireCbor {
    val instance = Cbor { ignoreUnknownKeys = true }
}
// --8<-- [end:wire-cbor]

// A persisted CBOR model, like the `CacheEntry` docs/cbor.md checks.
// --8<-- [start:cache-entry]
@Serializable
data class CacheEntry(
    val key: String,
    val value: String,
)
// --8<-- [end:cache-entry]
